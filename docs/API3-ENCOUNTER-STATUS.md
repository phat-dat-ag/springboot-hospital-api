# Change encounter status

This implements the third endpoint in [the learning plan](API-LEARNING-PLAN.md):
`PATCH /api/v1/hospital/encounters/{id}/status`. It requires ADMIN, uses the
existing response envelope, and updates one encounter with one history event
and one clinical audit event in a single PostgreSQL transaction.

## Try it step by step

1. Apply the existing V1–V9 migrations to your learning database. No new migration
   or automatic seed data is needed. See [DATABASE.md](DATABASE.md).
2. Register a synthetic patient through the registration endpoint.
3. Execute the one-time doctor/scheduled-encounter SQL in the learning plan,
   binding the returned patient UUID and your authenticated user UUID.
4. Open Swagger UI and authenticate with an existing ADMIN token. Keep tokens
   in your client's secret storage. Do not paste them into documentation or logs.
5. Send this body to the PATCH endpoint using your encounter UUID:

   ```json
   {
     "expectedStatus": "scheduled",
     "status": "arrived"
   }
   ```

6. Use expectedStatus=arrived and status=in_progress to start the consultation.
   The assigned doctor must be active and their license must not be expired.
7. Use expectedStatus=in_progress and status=completed to finish the encounter.
   Verify the returned startedAt is preserved and endedAt is populated.
8. Repeat the last request. It returns 409 and creates no additional events.
9. Create another scheduled encounter to practice cancellation:

   ```json
   {
     "expectedStatus": "scheduled",
     "status": "cancelled",
     "reason": "Patient requested cancellation"
   }
   ```

Cancellation reasons are clinical history. Use synthetic reasons while learning.
They are stored in the encounter and history, never in operational logs or the
clinical audit metadata. API reasons are trimmed and limited to 1000 characters.
Both status fields are required lowercase enums; unknown values return 400 with
safe field/allowed-value diagnostics. A malformed UUID or JSON also returns 400.
Patient, doctor and actor assignment cannot be changed through this DTO.

## Response

HTTP 200, for example after starting care:

```json
{
  "success": true,
  "data": {
    "id": "11111111-1111-4111-8111-111111111111",
    "patientId": "22222222-2222-4222-8222-222222222222",
    "status": "in_progress",
    "startedAt": "2026-11-18T07:00:00Z",
    "updatedAt": "2026-11-18T07:00:00.123456Z"
  },
  "meta": {
    "timestamp": "2026-11-18T07:00:00.124Z"
  }
}
```

Null timestamps are omitted using the existing Jackson configuration. Correlation
and trace metadata follow the existing request infrastructure. updatedAt comes
from the PostgreSQL update trigger, not the service Clock.

## Lifecycle and failures

| Current state | Allowed next state | Additional rule |
| --- | --- | --- |
| scheduled | arrived | Active patient |
| scheduled | cancelled | Active patient; nonblank reason |
| arrived | in_progress | Active patient; assigned active, unexpired doctor |
| arrived | cancelled | Active patient; nonblank reason |
| in_progress | completed | Active patient; doctor assigned; startedAt present and not in the future |
| completed, cancelled, entered_in_error | none | 409; correction/reopening is outside this endpoint |

A doctor's license remains valid on its expiry date. Null expiry means
unspecified. Checks use the injected clinic Clock and its explicit timezone:
`app.clinic.time-zone=${CLINIC_TIME_ZONE:Asia/Bangkok}`. Supply the environment
variable to the application process to override it. Spring does not read a root
.env file automatically. Event timestamps are Instant values regardless of zone.
Doctor availability is rechecked when starting care; completion requires the
assignment and a start timestamp but does not re-evaluate license expiry.

| HTTP | Error code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST / INVALID_ENUM_VALUE | Invalid body/path or enum |
| 400 | ENCOUNTER_REASON_REQUIRED | Cancellation reason is missing/blank |
| 401 | INVALID_ACTOR_ID | Authenticated subject cannot be parsed as UUID |
| 401 / 403 | Existing security response | Authentication missing/invalid or ADMIN absent |
| 404 | ENCOUNTER_NOT_FOUND | No encounter with this UUID |
| 409 | ENCOUNTER_STATUS_CONFLICT | Current status differs from expectedStatus |
| 409 | ENCOUNTER_TRANSITION_NOT_ALLOWED | Transition is outside the table, including same-state requests |
| 409 | ENCOUNTER_PATIENT_INACTIVE | Patient is unavailable/soft-deleted |
| 409 | ENCOUNTER_DOCTOR_UNAVAILABLE | Doctor is missing, soft-deleted or expired |
| 409 | ENCOUNTER_START_REQUIRED | Completion lacks a valid start/doctor, or Clock precedes start |
| 409 | ENCOUNTER_LOCK_CONFLICT | Row lock wait/deadlock failed; refresh before deciding whether to retry |
| 500 | ENCOUNTER_CHANGE_FAILED | Encounter/history/audit persistence failed; transaction rolls back |

The service checks cancellation reason before reading the encounter. After
locking, expectedStatus takes precedence over transition/availability checks.
Thus a request can have more than one problem, but receives the first safe error.
This is not replay idempotency: repeating a successful request returns 409.
There is no automatic retry of clinical state changes.

## Code and transaction flow

```mermaid
sequenceDiagram
    participant Client
    participant Security
    participant Controller as EncounterController
    participant Service as EncounterStatusService
    participant DB as PostgreSQL
    Client->>Security: PATCH with ADMIN authentication
    Security->>Controller: Authenticated request
    Controller->>Service: Valid DTO, encounter UUID, authenticated subject
    Service->>DB: BEGIN; SET LOCAL lock_timeout = 3s
    Service->>DB: Lock encounter FOR UPDATE
    Service->>Service: Compare expectedStatus; validate transition
    Service->>DB: Lock/check patient, then doctor when starting
    Service->>DB: Update header; append history and clinical audit
    Service->>DB: Flush; refresh trigger timestamps
    DB-->>Service: COMMIT or rollback on failure
    Service-->>Controller: Response DTO after successful commit
    Controller-->>Client: 200 envelope
```

Lock order is **encounter → patient → doctor**. Doctor is locked only when
starting care. PostgreSQL transaction-local lock_timeout applies to each lock
wait, including foreign-key locks, and automatically resets at transaction end.
Three seconds bounds each wait, not the entire HTTP request or pool acquisition.
Future workflows must follow compatible lock ordering.

The encounter lock serializes competing writes. Checking expectedStatus after
acquiring it prevents a queued request from acting on an old screen state.
Patient/doctor locks keep the checked active flags stable until commit. Lazy
many-to-one mappings avoid fetching unrelated clinical data. No version column,
collections, cascade deletes or schema modifications are introduced.

One Clock instant supplies startedAt or endedAt, history.occurredAt and
audit.occurredAt. createdAt remains database-owned and unchanged. The audit
mapping now permits an explicit event instant using DynamicInsert; registration
still omits the timestamp and receives its existing PostgreSQL default.
History/audit are immutable JPA events with database mutation guards.

Only the encounter status, transition timestamps and cancellation reason are
changed. Read mappings intentionally omit unused encounter/doctor columns and
do not support encounter/doctor creation. SQL fixtures remain manual.
The endpoint does not certify completion of notes, diagnoses, labs or prescriptions,
and it is not a production discharge/correction workflow.

## INFO logging

All normal workflow stages use INFO; none requires DEBUG. The existing MDC
correlationId and traceId connect these events with http.request.completed:

| Event | Level | Stage |
| --- | --- | --- |
| encounter.status.started | INFO | validation |
| encounter.status.lock.started | INFO | encounter_lock |
| encounter.status.validated | INFO | transition_validation |
| encounter.status.history.prepared | INFO | history |
| encounter.status.audit.prepared | INFO | audit |
| encounter.status.flushed | INFO | awaiting_commit |
| encounter.status.completed | INFO | committed; controlled from/to status fields |
| encounter.status.rejected | WARN | Safe errorCode |
| encounter.status.persistence.failed | ERROR | Safe errorCode and exception class |
| encounter.status.transaction.failed | ERROR | A prepared change did not commit |

The completion event runs after commit. Prepared/flushed events do not claim
success. Logs exclude patient/actor IDs, names, reasons, request bodies, tokens
and raw exception messages. No duplicate exception stack is added.

OpenSearch examples:

```text
service: "hospital-api" and event: "encounter.status.completed"
service: "hospital-api" and event: "encounter.status.rejected"
traceId: "<trace ID from the response>"
```

Rebuild/restart the Hospital application to load changes. The task does not
restart your Compose stack.

## Verification

From the root repository:

```powershell
mvn -pl springboot-hospital-api '-Dtest=EncounterTransitionsTest,EncounterStatusServiceTest,EncounterControllerTest' test
```

For PostgreSQL checks, explicitly provide an isolated disposable database named
clinical_encounter_test. The test refuses other database names, applies Flyway
and validates mappings; it never starts Docker or resets/deletes clinical rows.
It inserts committed synthetic fixtures and test-only failure triggers.

```powershell
$env:CLINICAL_ENCOUNTER_TESTS = 'true'
$env:CLINICAL_ENCOUNTER_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55442/clinical_encounter_test'
$env:CLINICAL_ENCOUNTER_DB_USER = '<isolated test role>'
$env:CLINICAL_ENCOUNTER_DB_PASSWORD = '<isolated test password>'
mvn -pl springboot-hospital-api test
mvn -pl springboot-hospital-api package -DskipTests
```

Concurrency tests use separate worker threads and transactions. A second
connection deliberately holds locks to verify timeout and concurrent soft-delete
behavior. History/audit INSERT and encounter UPDATE failures are injected
separately to prove rollback. A registration regression checks that the shared
audit timestamp mapping still uses the database default.

Inspect records with bound parameters:

```sql
SELECT status, started_at, ended_at, cancellation_reason, created_at, updated_at
FROM medical_encounters WHERE id = :encounter_id;

SELECT from_status, to_status, actor_user_id, reason, occurred_at
FROM encounter_status_histories WHERE encounter_id = :encounter_id
ORDER BY occurred_at, id;

SELECT patient_id, actor_user_id, operation, entity_type, occurred_at
FROM clinical_audit_logs WHERE entity_id = :encounter_id
    AND entity_type = 'medical_encounters'
ORDER BY occurred_at, id;
```

Use synthetic data only; SQL result sets may contain clinical information.

## Files introduced or updated

Under src/main/java/com/hospitalmanagement/hospital:

| Path | Purpose |
| --- | --- |
| encounter/controller/EncounterController.java | Thin PATCH endpoint and OpenAPI contract |
| encounter/dto/request/ChangeEncounterStatusRequest.java | Validated status/reason input |
| encounter/dto/response/EncounterStatusResponse.java | Response without entity serialization |
| encounter/enums/EncounterStatus.java | Lowercase database and JSON values |
| encounter/entity/MedicalEncounter.java | Minimal encounter mapping and controlled mutation |
| encounter/entity/Doctor.java | Read subset for active/license checks |
| encounter/entity/EncounterStatusHistory.java | Immutable history event |
| encounter/repository/MedicalEncounterRepository.java | Encounter write lock |
| encounter/repository/DoctorRepository.java | Doctor write lock |
| encounter/repository/EncounterStatusHistoryRepository.java | History persistence |
| encounter/service/EncounterStatusService.java | Validated service contract |
| encounter/service/EncounterTransitions.java | Pure transition table |
| encounter/service/impl/EncounterStatusServiceImpl.java | Transaction, locks, validation, audit and INFO logs |
| encounter/config/EncounterWorkflowConfig.java | Explicit clinic Clock/timezone |
| patient/repository/PatientRepository.java | Patient write lock |
| patient/entity/ClinicalAuditLog.java | Optional explicit event instant; preserves default |
| common/exception/ErrorCode.java | Safe encounter errors |
| security/config/UserSecurityConfig.java | Narrow ADMIN PATCH rule |
| logging/StructuredJsonEncoder.java | Controlled from/to status fields |

Also updated src/main/resources/application.properties with the clinic timezone.
New tests under src/test/java/com/hospitalmanagement/hospital/encounter are
EncounterTransitionsTest, EncounterStatusServiceTest, EncounterControllerTest and
EncounterStatusIntegrationTest. Existing HospitalFoundationTest,
OpenApiDocumentationTest and JwtAuthenticationTest receive the new service mock
for database-free contexts; OpenApiDocumentationTest verifies the new contract.
README.md, API-LEARNING-PLAN.md, DATABASE.md and API1-PATIENT-REGISTRATION.md link
or explain the new behavior alongside this guide.

Validation on 2026-11-18: the full module suite passed with 281 tests, zero
failures/errors and 20 expected database opt-in skips. All 13 encounter PostgreSQL
tests ran against an authorized isolated PostgreSQL 17.6 container. The remaining
database suites were not enabled. No application server or existing Compose
database was needed for the new tests. Postman CLI verification was unavailable
because its locally managed Private Cloud manifest could not be read.
Packaging, Compose configuration validation and whitespace checks also passed.
The isolated container and its temporary volume were removed after verification;
the existing Compose environment, User Service, JWKS and migrations were unchanged.
