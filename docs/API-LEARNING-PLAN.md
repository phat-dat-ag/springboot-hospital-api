# Learn Spring Boot and PostgreSQL through three Clinical APIs

## Goal and scope

Build three HTTP endpoints that a hospital reception/workflow application could
use. Complete them in order. Each adds a new skill without requiring you to
implement the entire hospital system.

| Order | API | Practical use | Main learning | Difficulty |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/hospital/patients` | Register a patient and contacts | DTO validation, JPA relationships, transactions, unique constraints | Intermediate foundation |
| 2 | `GET /api/v1/hospital/patients` | Find registered patients | Dynamic filtering, pagination, projections, query plans | Intermediate queries |
| 3 | `PATCH /api/v1/hospital/encounters/{id}/status` | Move a visit through reception and consultation | Lifecycle rules, row locks, concurrent requests, atomic history | Intermediate challenge |

Here, “three APIs” means exactly three HTTP endpoints. Do not build full CRUD
for every table. Registration and search form the first usable feature; encounter
status changes add a real business workflow. Laboratory and prescription APIs
can wait until you are comfortable with transactions and data integrity.

This document is a follow-along implementation guide. All three endpoints are
implemented; see [the registration guide](API1-PATIENT-REGISTRATION.md),
[the search guide](API2-PATIENT-SEARCH.md) and
[the encounter status guide](API3-ENCOUNTER-STATUS.md) for behavior and verification.
The guide itself does not execute migrations or start services.

## Before starting

1. Read [DATABASE.md](DATABASE.md), especially timestamp, soft-delete and
   clinical immutability policies.
2. Read [java-code-style.md](java-code-style.md) before writing Java.
3. Work in `springboot-hospital-api`, under the existing base package
   `com.hospitalmanagement.hospital`. The planned Clinical rename has not happened;
   do not introduce a second package root during these exercises.
4. Keep the current Spring Boot 4.1.2 and Java 17 baseline. Use `jakarta.persistence`
   and `jakarta.validation` imports, not older `javax` examples.
5. Keep PostgreSQL `clinical_db`, Flyway and Hibernate `ddl-auto=validate`.
   SQL lives in root `db/migration/hospital`. No schema change is required for
   these three endpoints. Preserve V1–V9; any future change starts at V10.
6. Use a separate local learning/test database with synthetic people. The
   existing migration test requires an empty `clinical_schema_test` database;
   normal API integration fixtures need a different isolated database or schema.
7. Start your existing development stack only when you intend to do so:

   ```powershell
   docker compose config --quiet
   docker compose up -d --build
   docker compose logs clinical-database-init flyway-postgres
   ```

8. Confirm all Hospital migrations through V10 succeeded. Sign in through User Service with an existing activated STAFF account for the patient APIs. Use STAFF for reception and the assigned DOCTOR account for examination start. Keep tokens in local client secret variables.
9. Verify the STAFF token with a filtered patient search. The existing technical authentication endpoint remains available to USER and ADMIN.

**Security for this learning slice.** Patient registration, search and limited demographics require STAFF. Encounter status authorization depends on the requested action and current doctor assignment. The current User Service role contract
uses USER=`1`, PATIENT=`2`, STAFF=`3`, DOCTOR=`4` and ADMIN=`5`.
[PAT-003](API4-PATIENT-DEMOGRAPHICS.md) uses the STAFF role for demographic
lookup. Audit actor UUIDs come from the validated JWT
subject, which the current User Service issues as its user UUID, never a request
body. A doctor UUID is a separate local clinical identifier.

### Existing code to reuse

| Existing component | How to use it |
| --- | --- |
| `common.response.APIResponse` | Keep the success/error envelope; return DTOs inside `data` |
| `common.response.APIMeta` | Keep timestamp/correlation metadata; do not redefine pagination here |
| `common.exception.BusinessException` and `ErrorCode` | Express expected failures; add specific conflict codes during implementation |
| `common.exception.GlobalExceptionHandler` | Centralize HTTP error mapping; keep SQL details out of responses |
| `common.controller.BaseController` | Its helper returns 200; explicitly construct a 201 response for registration |
| Existing security and logging | Reuse authentication, correlation and privacy behavior |

The current generic database-integrity handler returns a 400-style error.
For these APIs, deliberately add narrow mappings for known unique conflicts to
409 and state conflicts to 409. Do not convert every database exception to
“duplicate patient.” Preserve unrelated existing error behavior.

## Shared implementation approach

Follow the existing package layout: `controller`, `service`, `service.impl`,
`dto.request`, `dto.response`, then introduce `entity` and `repository` as needed.
Keep controllers thin and put transaction boundaries on public service methods.
Do not create generic CRUD frameworks or map all 28 tables up front.

Use constructor injection and explicit DTO mapping first. Never serialize JPA
entities directly. Map database enums to lowercase values deliberately;
`EnumType.STRING` alone with uppercase constants would not match the current
CHECKs. API 1 now uses JPA 3.2 `@EnumeratedValue` and Jackson `@JsonValue` on its
typed enums to preserve the lowercase database and JSON values.

Use `UUID`, `LocalDate` and `Instant` for UUID, date and timestamptz fields.
For these exercises, use `@GeneratedValue(strategy = GenerationType.UUID)` on
UUID entity IDs and let JPA generate them for INSERT; the database UUID default
remains available for SQL clients that omit the ID. Do not also assign the same
entity IDs manually. Test the actual INSERT and returned UUID.

Database triggers own timestamps. Map created_at/updated_at as database-managed
columns, without application auditing callbacks or timestamp annotations that
write competing values. Flush then refresh where a response needs fresh defaults
or trigger-maintained timestamps. Map deleted_at explicitly; active patient
queries must include `deleted_at IS NULL`. Never use repository delete methods
for these records: the database prohibits DELETE and TRUNCATE.

Suggested incremental entity set:

| API | Introduce only these entity mappings |
| --- | --- |
| 1 | Patient, PatientContact, ClinicalAuditLog |
| 2 | MedicalRecordAccessLog; reuse Patient |
| 3 | MedicalEncounter, Doctor, EncounterStatusHistory; reuse audit/patient mappings |

Map only required columns initially if appropriate, but every required INSERT
column must either be mapped and supplied or be omitted with a valid DB default.
Hibernate validation does not replace checking the actual persisted records.
Keep many-to-one relationships lazy; avoid bidirectional collections unless
you need them. Two explicit patient/contact saves in one transaction are fine.

## API 1 — Register a patient with contacts

### Contract

`POST /api/v1/hospital/patients`

```json
{
  "medicalRecordNumber": "LEARN-0001",
  "givenName": "An",
  "familyName": "Example",
  "dateOfBirth": "1995-06-15",
  "sexAtBirth": "female",
  "contacts": [
    {
      "contactType": "self",
      "phone": "+66000000000",
      "isPrimary": true
    }
  ]
}
```

Keep MRN input explicit for this exercise: learning a separate identifier
allocation service is unnecessary here. Trim it, require 1–40 characters and
preserve case; do not silently introduce new case-insensitive uniqueness rules.
Do not expose externalUserId assignment yet. A patient does not need a user account.

Return HTTP **201**, with `APIResponse<PatientRegistrationResponse>` containing
the patient UUID, MRN, demographics, createdAt and contact UUIDs. Omit null envelope
fields as the existing application does. No new patient-detail GET is required.

| Condition | Result |
| --- | --- |
| Valid registration | 201 |
| Invalid fields, future birth date, invalid contacts | 400 |
| MRN already exists, including on a soft-deleted patient | 409 |
| Missing/invalid token | 401 |
| Authenticated caller without required STAFF authority | 403 |

### Step-by-step work

1. Write request/response DTOs before the controller. Use `@Valid` on nested
   contacts, `@NotBlank`/`@Size` for names/MRN, and a past-or-present birth-date
   rule. Given name is required; family name and birth date remain optional.
2. Match SQL values: sex is female/male/intersex/unknown; contact type is
   self/emergency/guardian. For this bounded exercise allow at most five contacts
   per request. This is an API limit, not a new database constraint.
3. Add a service validator: each contact needs at least one nonblank phone,
   email or address; non-self contacts need a name. Permit at most one primary
   contact per contact type in this request. Test missing versus empty values.
4. Map Patient and PatientContact, including explicit table/column names and
   the patient FK. Avoid `CascadeType.ALL` and orphan removal: they can attempt
   forbidden deletes. Implement repositories with UUID IDs.
5. Implement one `@Transactional` service method: validate, insert patient,
   insert contacts, insert one `clinical_audit_logs` event for the registration,
   flush, refresh timestamps if needed, and map the response.
6. Record audit operation=create, entity_type=patients, entity_id=patient UUID,
   patient_id=patient UUID, actor_type=user, actor_user_id=validated subject.
   Do not copy names, phones, JWTs or request bodies into the audit event.
7. An MRN existence check can improve the message, but the UNIQUE constraint
   is authoritative when requests race. Identify the known constraint when
   translating an integrity exception to a safe 409 response. Let the transaction
   roll back; do not catch a SQL failure and continue saving in that transaction.
8. Implement the thin controller, STAFF authorization rule and OpenAPI request/
   response examples. Return 201 explicitly rather than the existing 200 helper.
9. Test the complete transaction, not only mocked repository calls.

### Acceptance tests

- [ ] Valid patient without contacts: 201 and one patient/audit row.
- [ ] Valid patient with two contacts: 201 and correctly linked contact rows.
- [ ] Blank given name, overlong MRN, future birth date: 400, no writes.
- [ ] Emergency contact without a name: 400, no writes.
- [ ] Two primary self contacts: 400, no partial registration.
- [ ] Duplicate MRN: 409; original patient stays unchanged.
- [ ] Two concurrent registrations with one MRN: one 201, one 409, one patient.
- [ ] A forced contact or audit INSERT failure rolls back the patient too.
- [ ] UUID/timestamps exist and are mapped correctly in the response.
- [ ] Authentication and authorization return the expected 401/403 responses.

Verify with parameterized SQL using the returned patient UUID:

```sql
SELECT id, medical_record_number, created_at, updated_at, deleted_at
FROM patients WHERE id = :patient_id;

SELECT id, patient_id, contact_type, is_primary
FROM patient_contacts WHERE patient_id = :patient_id;

SELECT operation, entity_type, entity_id, actor_user_id
FROM clinical_audit_logs WHERE patient_id = :patient_id;
```

The `:name` notation means a bound parameter in your SQL client/JdbcTemplate;
it is not directly executable unmodified in psql.

**Checkpoint:** explain why `@Transactional` belongs on the service and why
an exists-query alone cannot prevent duplicate MRNs.

## API 2 — Search patients with filtering and pagination

### Contract

`GET /api/v1/hospital/patients?medicalRecordNumber=LEARN-0001&page=0&size=20`

Also support `name=an`, optional `dateOfBirth=1995-06-15`, and
`sort=createdAt,desc`. Combine supplied filters with AND; name matches given OR
family name, case-insensitively by substring. MRN uses exact, case-sensitive
matching. For this learning API require at least one search filter to avoid
unrestricted browsing. Reject blank name filters and names shorter than two
characters; do not require two characters for an exact MRN lookup.

Return HTTP **200**, even when nothing matches:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "11111111-1111-4111-8111-111111111111",
        "medicalRecordNumber": "LEARN-0001",
        "givenName": "An",
        "familyName": "Example",
        "dateOfBirth": "1995-06-15"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

This example focuses on `data`; the real response also keeps existing APIMeta.
Do not return contacts, allergies or entire encounter collections in search.
Omit any fields not needed by the lookup screen. Return 400 for invalid dates,
negative pages, sizes outside 1–100, unknown sort fields or missing filters.

### Step-by-step work

1. Write a search-criteria DTO and a stable page-response DTO. Do not expose
   Spring's internal Page JSON structure as your public contract.
2. Allow sorting only by createdAt or medicalRecordNumber, with asc/desc.
   Always add UUID as a final tie-breaker. This makes ordering deterministic,
   although offset pages can still shift as other transactions insert records.
3. Start with exact MRN lookup, then add name/date filters using Spring Data
   Specifications or a single parameterized repository query. Pick one approach
   and keep it consistent. Never concatenate raw input into SQL.
4. Include `deleted_at IS NULL` in every search path and its count query.
   Escape `%`, `_` and your escape character if using LIKE so names are literal
   substrings rather than caller-supplied wildcard expressions.
5. Apply database pagination. Do not load all patients and slice a Java list.
   Avoid joins to contact collections: they can duplicate patients and break
   counts/pages. DTO mapping should not trigger a query per returned patient.
6. Record one `medical_record_access_logs` event per returned patient, with
   operation=search, actor_type=user, actor_user_id=validated subject,
   purpose_code=patient_lookup, outcome=allowed, entity_type=patients and
   entity_id=patient UUID. Do not record the search text or result payload.
7. Because step 6 writes logs, the outer service transaction must NOT be
   `readOnly=true`. Make the bounded search and its access-log inserts one
   transaction; if logging fails, fail the request rather than returning an
   unaudited result. Write no patient access rows for an empty page. The current
   log schema cannot represent a patientless search attempt; note this boundary
   rather than inventing a fake patient UUID.
8. Add controller parameter validation, the narrowly scoped STAFF route rule,
   OpenAPI examples and safe 400 errors.
9. Run the acceptance tests and inspect actual PostgreSQL queries.

### PostgreSQL exercise

Use synthetic test data to compare an exact MRN lookup with a name substring
search using `EXPLAIN (ANALYZE, BUFFERS)` on SELECT statements. The MRN UNIQUE
index supports equality. An ordinary B-tree does not generally accelerate a
leading-wildcard name search. A sequential scan on a small table can be sensible;
do not add an index just to force a different plan.

Only after measuring a larger dataset, consider a separate optional V10 migration
for a suitable name-search index. Extensions and new indexes are not required
to finish these three APIs. Document what a chosen index would support.

### Acceptance tests

- [x] Exact MRN, mixed-case name and birth-date filters work independently.
- [x] Multiple filters combine correctly; given/family-name OR stays grouped.
- [x] Soft-deleted patients never appear, including through exact MRN lookup.
- [x] Empty results: 200 with items=[] and totalElements=0.
- [x] Zero/negative/oversized sizes, missing filters and bad sorts: 400.
- [x] Repeated requests against unchanged data return the same stable ordering.
- [x] SQL-like input is treated as data, and literal `%`/`_` work as specified.
- [x] No N+1 contact/encounter loads; page count is correct.
- [x] Returned patients have access-log rows with the authenticated actor UUID.
- [x] A failed access-log insert prevents a successful result response.
- [x] 401/403 behavior is covered.

**Checkpoint:** explain the difference between filtering in SQL and in Java,
why pagination needs stable ordering, and why this GET uses a write transaction.

## API 3 — Change encounter status with history

### One-time prerequisite: a scheduled encounter

You deliberately have no doctor-management or encounter-creation endpoint in
this three-endpoint exercise. Create a synthetic doctor and scheduled encounter
through a local SQL client, using the patient UUID from API 1. This fixture is
manual learning data, not a versioned migration or automatic application seed.

Execute the following in your isolated learning database with `:patient_id`
bound to that patient UUID and `:actor_user_id` to your authenticated user UUID.
Use a fresh encounter number if repeating the fixture. Wrap it in a transaction.

```sql
BEGIN;
WITH new_doctor AS (
    INSERT INTO doctors (license_authority, license_number, given_name)
    VALUES ('LEARNING_ONLY', gen_random_uuid()::text, 'Example doctor')
    RETURNING id
), new_encounter AS (
    INSERT INTO medical_encounters (
        encounter_number, patient_id, doctor_id, status, scheduled_at
    )
    SELECT 'LEARN-VISIT-0001', :patient_id, id, 'scheduled', now()
    FROM new_doctor
    RETURNING id, patient_id
), initial_history AS (
    INSERT INTO encounter_status_histories (
        encounter_id, from_status, to_status, actor_user_id, reason
    )
    SELECT id, NULL, 'scheduled', :actor_user_id, 'Learning fixture'
    FROM new_encounter
    RETURNING encounter_id
), initial_audit AS (
    INSERT INTO clinical_audit_logs (
        patient_id, actor_user_id, actor_type, operation, entity_type, entity_id
    )
    SELECT patient_id, :actor_user_id, 'user', 'create', 'medical_encounters', id
    FROM new_encounter
    RETURNING entity_id
)
SELECT id AS encounter_id FROM new_encounter;
COMMIT;
```

### Contract

`PATCH /api/v1/hospital/encounters/{id}/status`

```json
{
  "expectedStatus": "scheduled",
  "status": "arrived",
  "reason": null
}
```

Return **200**, with encounter UUID, patient UUID, status, startedAt, endedAt
and refreshed updatedAt inside `data`. The service controls event timestamps.
Do not accept patient/doctor reassignment, actorUserId or arbitrary encounter
fields in this DTO.

For this bounded exercise implement only these transitions:

| Current | Allowed target | Required behavior |
| --- | --- | --- |
| scheduled | arrived | Append status history and audit |
| scheduled | cancelled | Nonblank cancellation reason |
| arrived | in_progress | Assigned active doctor; set started_at |
| arrived | cancelled | Nonblank cancellation reason |
| in_progress | none in P03 | Completion is deferred to P05 |
| completed, cancelled, entered_in_error | none in this exercise | Return 409 for attempted changes |

Return **404** for an unknown encounter and **409** for an expectedStatus
mismatch or disallowed transition. Unknown state values, invalid UUIDs and
missing cancellation reasons return **400**. A repeated request whose target
already equals the current state returns 409; it does not append duplicate
history. Document this behavior instead of claiming request replay idempotency.

The database allows additional correction states. Completed → entered_in_error
is deliberately outside this exercise: it needs a separate correction workflow.
Do not relax the database immutability trigger to accommodate a generic PATCH.

### Step-by-step work

1. Model the request DTO and lowercase status enum/converter. Write the transition
   table as a small pure Java function and unit-test it before persistence.
2. Map MedicalEncounter and EncounterStatusHistory; map Doctor only as far as
   needed to validate assignment, soft deletion and license expiry. Reuse the
   audit mapping. Keep all histories append-only.
3. Add a repository query for an encounter with a pessimistic write lock,
   equivalent to `SELECT ... FOR UPDATE`, inside a service transaction. There
   is no version column now, so do not add `@Version` without a new migration.
4. After acquiring the lock, compare the current state with expectedStatus.
   The row lock alone serializes writes; expectedStatus also rejects a request
   based on an older screen state. Both checks belong inside the transaction.
5. Load/check the referenced patient is active. Before starting consultation,
   check the doctor exists, is not soft-deleted and is not expired as of the
   service's configured clinic date. A missing expiry date means unspecified,
   not automatically invalid. Use an injected Clock and explicit clinic timezone
   for date-based rules, with a configurable Asia/Ho_Chi_Minh clinic default.
   Lock patient/doctor rows consistently if their active flags must remain stable
   during this write. Keep a fixed lock order across future workflows.
6. Validate the transition, set only its allowed fields and use a single Clock
   instant for the change. Arrival is recorded in status history. Starting the
   examination sets startedAt. Populate cancellation_reason for
   cancellation. Existing DB CHECKs are the last integrity boundary.
7. In the SAME transaction append one history row with from/to status, actor,
   reason and event instant, plus one clinical audit row. Use audit operation
   `update` for arrival and start, and `cancel` for cancellation. Do not
   invent unsupported audit operations such as `complete`.
8. Flush, refresh the trigger-maintained updatedAt and return a response DTO.
   Never update or delete existing histories. History and audit inserts must
   roll back if the encounter update fails, and vice versa.
9. Map lock timeout/concurrency failures to a documented safe conflict response;
   keep transactions short and configure a bounded lock wait. Do not silently
   retry a clinical state change. Ordinary sequential invalid transitions should
   be rejected by the service before SQL errors occur.
10. Use the existing controller, STAFF/DOCTOR route admission, action authorization and OpenAPI lifecycle examples.
11. Run two simultaneous requests using different connections/transactions.
    A test with two calls sharing one transaction does not prove concurrency.

The endpoint updates only the encounter header. Completion here does not certify
that all laboratory/prescription/note workflows are finished. Those aggregate
clinical rules are a later feature, not an implied production discharge policy.

### Acceptance tests

- [x] scheduled → arrived → in_progress succeeds for the appropriate actors. Completion is blocked.
- [x] Each successful request adds exactly one matching history and audit row.
- [x] started_at/ended_at are set correctly; created_at is preserved.
- [x] Unknown encounter: 404; malformed UUID/state: 400.
- [x] Invalid transition and stale expectedStatus: 409 with no changes.
- [x] Cancel without reason: 400; valid cancellation preserves its reason.
- [x] Soft-deleted patient or unavailable/expired doctor blocks starting care.
- [x] Completion without a start timestamp is rejected.
- [x] Completed/cancelled visits cannot be reopened by this endpoint.
- [x] History/audit failure rolls back status and timestamps.
- [x] Two requests with expectedStatus=arrived: one transition succeeds;
      the other sees the committed new status and receives 409.
- [x] Returned updatedAt reflects the database trigger, not a stale JPA value.
- [x] 401/403 behavior is covered.

Verify with bound parameters:

```sql
SELECT status, started_at, ended_at, created_at, updated_at
FROM medical_encounters WHERE id = :encounter_id;

SELECT from_status, to_status, actor_user_id, occurred_at
FROM encounter_status_histories
WHERE encounter_id = :encounter_id
ORDER BY occurred_at, id;

SELECT operation, entity_id, actor_user_id, occurred_at
FROM clinical_audit_logs
WHERE entity_type = 'medical_encounters' AND entity_id = :encounter_id
ORDER BY occurred_at, id;
```

**Checkpoint:** explain why a transaction without a row lock does not by itself
prevent both requests from validating the same earlier state.

## A manageable working schedule

Treat these as focused sessions, not deadlines. Move on only when each checkpoint
and its acceptance tests pass.

| Session | Deliverable |
| --- | --- |
| 1 | Read schema/conventions; confirm database and authenticated subject; sketch DTOs |
| 2 | API 1 mappings, validation and transactional registration |
| 3 | API 1 controller, error mapping and PostgreSQL integration tests |
| 4 | API 2 filters, DTO pagination and access logging |
| 5 | API 2 edge cases, query inspection and documentation |
| 6 | API 3 fixture and transition-function unit tests |
| 7 | API 3 locking, transactional history/audit and controller |
| 8 | API 3 concurrency tests; review all three contracts and security tests |

For each API: contract → validation → mapping/query → service → controller →
real PostgreSQL verification → documentation. Use small commits when you choose
to commit; no automatic commit is part of this guide.

## Testing and completion

Use fast unit tests for transition/validation logic, controller tests for JSON
and HTTP statuses, and PostgreSQL integration tests for constraints, transactions,
query behavior and locking. H2 does not establish compatibility with these
PostgreSQL triggers and partial indexes. Use a dedicated PostgreSQL instance,
or add Testcontainers later if you want automatic disposable database setup.

Run from repository root after implementing a slice:

```powershell
mvn -pl springboot-hospital-api test
mvn -pl springboot-hospital-api package -DskipTests
docker compose config --quiet
```

Add API-specific PostgreSQL tests with their own opt-in configuration rather than
reusing the clean-database migration test against a populated development DB.
Database-prohibited deletes mean ordinary delete-all cleanup is inappropriate:
use rolled-back test fixtures for sequential integration tests, and a disposable
database for committed/concurrent tests. Do not reset shared development data.

You are finished with this learning slice when all three contracts work through
the existing security layer, the listed acceptance tests pass, and you can
explain the SQL and transaction behavior without relying on annotations alone.
You do not need to implement the remaining tables to complete this plan.

## Official references to read at the relevant step

- API 1: [Spring Data JPA transaction boundaries](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html).
- API 2: [Spring Data JPA Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html).
- API 2 query exercise: [PostgreSQL 17 index types and pattern matching](https://www.postgresql.org/docs/17/indexes-types.html).
- API 3: [Spring Data JPA locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
  and [PostgreSQL 17 row-level locking](https://www.postgresql.org/docs/17/explicit-locking.html).

These references support the transaction, query and locking techniques. The
three endpoint contracts, ordering and session schedule are recommendations for
this repository and its current schema.
