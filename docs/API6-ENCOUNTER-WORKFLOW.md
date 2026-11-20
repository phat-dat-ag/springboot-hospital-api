# P03 reception and assigned encounter workflow

The synthetic single-clinic workflow uses the existing five-role JWT
contract and the configurable Asia/Ho_Chi_Minh clinic timezone.

| Operation | STAFF | DOCTOR | USER, PATIENT, ADMIN |
| --- | --- | --- | --- |
| GET /api/v1/hospital/encounters | Clinic-wide | 403 | 403 |
| GET /api/v1/hospital/encounters/assigned | 403 | Currently assigned visits | 403 |
| GET /api/v1/hospital/encounters/{id} | Clinic-wide | Currently assigned visit | 403 |
| PATCH /api/v1/hospital/encounters/{id}/status | Arrival and pre-start cancellation | Start assigned visit | 403 |

Missing or invalid JWTs receive 401. The [ENC-001 contract](API3-ENCOUNTER-STATUS.md)
documents allowed transitions and the completion restriction.

## Queue filters

Both queues take optional date (YYYY-MM-DD), status (one exact lowercase
encounter status), page (zero-based, default 0), and size (1–50, default 20).
The default date is the current clinic date. The date uses scheduledAt, or
createdAt for legacy encounters with no schedule. It is evaluated using the
local day's inclusive start and exclusive end. Reception defaults to scheduled
and arrived. The assigned queue defaults to scheduled, arrived, and in_progress.
Terminal states require an explicit filter.

~~~http
GET /api/v1/hospital/encounters?date=2026-11-19&status=scheduled&size=20
Authorization: Bearer <STAFF access token>

GET /api/v1/hospital/encounters/assigned
Authorization: Bearer <DOCTOR access token>
~~~

The APIResponse data includes date, page, size, hasNext, and items. PostgreSQL
filters before pagination and sorts by effective schedule then encounter UUID.
There is no total. Under stable records the same query yields the same order.
New registrations and state changes can shift rows between offset pages.
Doctor scope comes solely from the authenticated account. Client-supplied
doctor IDs never influence scope.

## Workflow details

~~~http
GET /api/v1/hospital/encounters/11111111-1111-4111-8111-111111111111
Authorization: Bearer <STAFF or assigned DOCTOR access token>
~~~

The detail and queue summaries contain the encounter UUID and number, patient
UUID and display name, assigned doctor UUID and display name when present,
status, and relevant workflow timestamps. Arrival time is derived from the
first committed arrived history event. Only STAFF detail reads of cancelled
visits include the cancellation reason. Clinical content and patient contacts
are excluded. All successful reads send Cache-Control: no-store.

Inactive patient records are excluded. An unassigned doctor receives the
same 404 as a missing encounter. A doctor must have an active linked profile
and a current active role-4 User account. License expiry does not prevent
reading a currently assigned encounter. Account verification timeouts and
unavailability return 503, and malformed responses return 502.

## Audit and verification

Each disclosed queue row and detail record creates a patient access audit
with the actor, encounter and purpose. A failed mandatory audit returns a
controlled 500 before disclosing records. Missing and out-of-scope requests
follow the existing operational denial logging convention. Clinical writes
continue to record status history and clinical audit atomically. Operational
logs omit clinical payloads and encounter IDs.

Run the Hospital Maven Wrapper with CLINICAL_ENCOUNTER_TESTS=true against a
disposable clinical_encounter_test PostgreSQL 17.6 database. Supply
CLINICAL_ENCOUNTER_JDBC_URL, CLINICAL_ENCOUNTER_DB_USER, and
CLINICAL_ENCOUNTER_DB_PASSWORD. EncounterStatusIntegrationTest verifies queue
scope, read audit failure, transitions, write rollback, and concurrent writes.
For a live smoke test, use isolated User, JWKS, MySQL and PostgreSQL, one
STAFF account and two linked DOCTOR accounts. Register using P02, receive
as STAFF, start as the assigned doctor, and verify wrong-doctor access,
forbidden completion, and a separate pre-start cancellation.

The P03 verification run passed 334 Maven tests with zero failures or skips.
The live workflow passed with two User-issued doctor identities. It verified
P02 replay, cross-doctor denial, arrival and examination start, cancellation,
completion denial, the OpenAPI routes, and the persisted audit actors.
Synthetic identifiers, clinical text and JWTs were absent from Hospital's
operational logs.
