# Patient search

Implemented endpoint: `GET /api/v1/hospital/patients`. This completes the search
feature in [the learning plan](API-LEARNING-PLAN.md), using the existing schema
from [DATABASE.md](DATABASE.md). Encounter status changes remain planned.

## Contract

Authentication uses the existing bearer token/JWKS configuration. This GET route
requires STAFF, matching patient registration. It does not change permissions
for the technical endpoints or alter token issuers, claims, algorithms or keys.

Examples:

```text
GET /api/v1/hospital/patients?medicalRecordNumber=LEARN-0001
GET /api/v1/hospital/patients?name=an&page=0&size=20&sort=createdAt,desc
GET /api/v1/hospital/patients?name=an&dateOfBirth=1995-06-15&sort=medicalRecordNumber,asc
```

Use Swagger UI's existing bearer authorization and patient search operation to
try these against your configured Hospital API base URL. Search filter values
must be URL encoded; for example, encode a literal percent sign as `%25`.

| Parameter | Behavior |
| --- | --- |
| medicalRecordNumber | Optional exact, case-sensitive match; trimmed; 1–40 characters if supplied |
| name | Optional case-insensitive literal substring of given OR family name; trimmed; 2–100 characters if supplied |
| dateOfBirth | Optional exact date in ISO yyyy-MM-dd format |
| page | Zero-based integer, default 0; must be non-negative |
| size | Integer from 1–100, default 20 |
| sort | Default createdAt,desc; field must be createdAt or medicalRecordNumber and direction asc or desc |

At least one of medicalRecordNumber, name or dateOfBirth is required. Supplied
filters combine with AND; the given/family-name OR is grouped inside the name
predicate. Empty/blank supplied name or MRN is invalid. A one-character MRN is
valid; the two-character minimum applies only to name. Sort spelling is exact,
including case and the comma separator. UUID ascending is always the final sort
tie-breaker. Page offsets beyond JPA's integer offset range return 400.

Soft-deleted patients are excluded from both the page and its total count.
Multiple contacts do not duplicate rows because the search does not join contacts.

## Response

HTTP 200 uses APIResponse with APIMeta and this stable `data` shape:

```json
{
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
```

Only summary fields are selected and returned. Contacts, external account links,
allergies, encounters and other clinical records are not included. Optional null
fields follow the application's existing null-omission policy. No matches return
200 with items=[], totalElements=0 and totalPages=0. An out-of-range page of an
otherwise matching search has empty items while retaining the matching total.

| HTTP | Error |
| --- | --- |
| 400 | VALIDATION_ERROR for invalid bound query fields, dates, page, size or sort |
| 400 | PATIENT_SEARCH_FILTER_REQUIRED when no search filter is supplied |
| 400 | PATIENT_SEARCH_PAGE_OUT_OF_RANGE when the JPA offset limit is exceeded |
| 401 | Missing/invalid token; INVALID_ACTOR_ID for an invalid authenticated UUID subject |
| 403 | Caller lacks STAFF authority |
| 500 | PATIENT_ACCESS_LOG_FAILED when access events cannot be persisted; no successful results are returned |

Existing framework security responses remain unchanged. General unexpected
failures retain the existing INTERNAL_SERVER_ERROR handling. Responses and
application logs do not intentionally include SQL text or raw database errors.

## Implementation and transaction boundary

PatientController binds PatientSearchRequest using @ModelAttribute, @Valid and
@ParameterObject, keeping the query contract visible in OpenAPI. The new
PatientSearchService is separate from registration so that PatientServiceImpl's
registration logic remains focused.

PatientSpecifications builds parameterized Criteria predicates. It always adds
deleted_at IS NULL, then optional exact MRN, name and birth-date conditions.
LIKE uses `!` as its escape character and escapes literal `!`, `%` and `_`.
SQL-like strings are values, never query syntax. Only validated sort properties
reach Spring Data's pageable query.

The repository implements JpaSpecificationExecutor and uses its fluent DTO
projection to select PatientSummaryResponse fields directly. The database applies
pagination and counting; Java does not fetch and slice the whole patient list.
No contact/encounter associations are fetched for the result DTO.

The public search service method has a regular read/write @Transactional boundary,
because it also inserts access events. Each returned patient generates one row
in medical_record_access_logs with:

- patient_id and entity_id set to that patient's UUID;
- actor_user_id parsed from the authenticated JWT subject and actor_type=user;
- operation=search, purpose_code=patient_lookup, entity_type=patients;
- outcome=allowed and a PostgreSQL-generated occurred_at timestamp.

The immutable access entity uses typed lowercase enums, lazy patient references
and no cascade deletes. UUID references do not initialize patient proxies.
Search text and result payloads are not stored in access events or logged by
the search service. The optional correlation_id column is left unset: request
correlation IDs can be non-UUID strings, whereas this column is a UUID.

Access events are flushed before returning. Any persistence failure exits the
transaction with PATIENT_ACCESS_LOG_FAILED; earlier access INSERTs roll back.
An empty page produces no patient access events. The current schema requires a
patient_id, so it cannot represent a patientless failed/empty search event;
the existing HTTP completion log still captures the request outcome.

All access logs remain append-only under the existing database guards. No new
tables, migrations, indexes, timestamp callbacks or demonstration seeds are added.

## Structured business logging

PatientSearchServiceImpl uses SLF4J structured events for its stages, with the
existing MDC traceId and correlationId. It records counts and filter names,
never MRNs, search text, dates of birth, patient/actor IDs, result DTOs, tokens
or raw database exception messages.

| Event | Level | Meaning |
| --- | --- | --- |
| patient.search.started | INFO | Service validation starts |
| patient.search.rejected | WARN | Actor, required-filter or page-offset guard failed; includes errorCode |
| patient.search.query.started | INFO | Query starts; safe filter names, page, size and whitelisted sort |
| patient.search.query.completed | INFO | Query returns; resultCount and total element/page counts |
| patient.search.query.failed | ERROR | Query failed; exception class only |
| patient.search.access_logs.started | INFO | Access-log insertion starts; expected accessLogCount |
| patient.search.access_logs.flushed | INFO | INSERTs flushed, awaiting transaction commit |
| patient.search.access_logs.failed | ERROR | Access-log persistence failed; safe type, errorCode and count |
| patient.search.completed | INFO | Transaction committed; pagination, filter names, result totals and accessLogCount |
| patient.search.transaction.failed | ERROR | A prepared search transaction rolled back or has unknown completion status |

The INFO completion event is registered with TransactionSynchronization and runs
only after commit. It is not emitted merely because saveAllAndFlush returned.
Direct calls without managed transaction synchronization receive only an INFO
patient.search.prepared diagnostic, never a committed-success claim. Ordinary
request-binding/Bean Validation errors occur before the service body and retain
the existing HTTP completion error diagnostics.

The shared JSON encoder explicitly allows the new search.* metadata fields and
accessLogCount, preserving numeric JSON values. There is still one final
http.request.completed event from RequestLoggingFilter. Business failure events
do not duplicate exception stack traces; the existing request error owner retains
its usual sanitized exception handling.

Useful OpenSearch filters:

```text
service: "hospital-api" and event: "patient.search.completed"
service: "hospital-api" and event: "patient.search.access_logs.failed"
traceId: "<trace ID from the API response>"
```

Stage events and completion summaries are available at the default INFO level. To set
the service level explicitly in Spring configuration:

```properties
logging.level.com.hospitalmanagement.hospital.patient.service.impl.PatientSearchServiceImpl=INFO
```

No SQL/body/wire logging needs enabling. Rebuild/restart the Hospital API before
expecting these source changes in a running container; refresh the index-pattern
field list if the new fields are not yet selectable in OpenSearch Dashboards.

## Query evidence and limits

The PostgreSQL 17.6 integration test observes two SELECT statements and one
access INSERT for a full one-patient page: page projection, count and access log.
Hibernate reports zero entity loads/fetches for that search. This checks that
projection and patient references do not introduce N+1 reads. Spring Data may
omit the count query when a short page already establishes its total.

EXPLAIN (ANALYZE, BUFFERS), executed on synthetic test data, showed:

- Exact MRN lookup: Index Scan using patients_medical_record_number_key.
- Leading-wildcard name lookup: Seq Scan on patients.

These are observations on a small test database, not performance guarantees.
No name index or extension is introduced without a measured workload. Offset
pagination is deterministic for unchanged data, but concurrent inserts/deletions
can still shift results between separate requests. Page/count queries use the
existing default transaction isolation, not a long-lived snapshot across pages.

## Verification

Fast controller and service-policy tests, from repository root:

```powershell
mvn -pl springboot-hospital-api '-Dtest=PatientSearchControllerTest,PatientSearchServiceImplTest,PatientSearchLoggingTest' test
```

PostgreSQL integration uses a dedicated disposable database. Supply its test-only
credentials through the environment, never production credentials:

```powershell
$env:CLINICAL_SEARCH_TESTS = 'true'
$env:CLINICAL_SEARCH_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55441/clinical_search_test'
$env:CLINICAL_SEARCH_DB_USER = '<isolated test role>'
$env:CLINICAL_SEARCH_DB_PASSWORD = '<isolated test password>'
mvn -pl springboot-hospital-api -Dtest=PatientSearchIntegrationTest test
```

The test refuses a database name other than clinical_search_test. It applies
Flyway, validates JPA mappings, inserts unique synthetic fixtures and installs a
test-only failure trigger in that database. It never starts Docker, cleans a
schema, or deletes clinical rows. Fixtures remain for the database owner to
dispose of. Controller integration uses MockMvc, without an HTTP server or live
User/JWKS requests; the existing JWT tests separately cover token verification.

Verification on 2026-11-18: the full module suite passed with 196 tests, zero
failures/errors and 12 expected database opt-in skips. All eight search PostgreSQL
integration tests ran successfully, alongside query validation, authorization,
service guards and OpenAPI regressions. The developer's Compose environment was
not changed; only an explicitly authorized isolated PostgreSQL container was used.

Maven packaging also passes. The isolated test container and its temporary volume
are removed after verification; the existing Compose stack is not restarted.

After adding structured search logging, the full suite passed with 202 tests,
zero failures/errors and 20 database opt-in skips. Six logging tests verify stage
events, commit/rollback behavior, empty results, safe failure metadata and the
absence of patient values in encoded logs. Packaging passed again. PostgreSQL
tests were not rerun for this logging change, and no Docker resources were started.
