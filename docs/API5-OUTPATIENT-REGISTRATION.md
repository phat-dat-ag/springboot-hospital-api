# P02 outpatient doctor selection and encounter registration

Use a User-issued STAFF bearer token for both endpoints. USER, PATIENT, DOCTOR,
and ADMIN receive 403. An absent or invalid bearer receives 401. Successful
responses use the standard APIResponse envelope and Cache-Control: no-store.
The demo represents one synthetic outpatient clinic.

## GET /api/v1/hospital/doctors

Example: GET /api/v1/hospital/doctors?name=example&size=10

| Parameter | Contract |
| --- | --- |
| name | Optional case-insensitive literal substring of display name, at most 100 characters |
| size | 1–20 items, default 10 |
| after | Optional UUID from the preceding response's nextCursor. Keep the name filter unchanged |

The data object contains items with id, displayName, and optional
primarySpecialty. Ordering is ascending Hospital doctor UUID. Only valid
profiles with current active role 4 accounts are included. A null license
expiry is permitted; a dated license must be valid through the current clinic
date. Soft deletion makes a profile inactive in the Hospital schema.

A request verifies up to 20 candidates, with one lookup at a time per request
and at most eight concurrent lookups per Hospital instance shared with encounter
registration. The cursor identifies the last examined candidate and is omitted
when the local results are exhausted. Carry it forward even on an empty or
short page. Stable data yields no skipped or repeated candidates. Profile or
account changes between pages can move the effective eligibility boundary.
There is no exact eligible total. HTTP calls use existing connect/read
timeouts. An authoritative missing account, inactive account or wrong role
excludes the candidate. A timeout, unavailable dependency or saturated lookup
limit gives 503. An invalid upstream account response gives 502. Results are
withheld on either error.

## POST /api/v1/hospital/encounters

Send Idempotency-Key with 1–128 ASCII letters, digits, dots, underscores,
tildes, or hyphens. Generate a new key for each intended new visit.

Synthetic body:

```json
{
  "patientId": "11111111-1111-4111-8111-111111111111",
  "doctorId": "22222222-2222-4222-8222-222222222222",
  "chiefComplaint": "Routine visit"
}
```

Chief complaint is required and limited to 2,000 characters. An optional
scheduledAt accepts a timestamp with offset. Registration uses the clinic
calendar (Asia/Ho_Chi_Minh by default). Explicit scheduling accepts today
or a future clinic day through year 9999. A walk-in uses the current server
instant. PostgreSQL stores instants with time zone at microsecond precision.

201 ENCOUNTER_REGISTERED returns data.id, encounterNumber, patientId, doctorId,
status (scheduled), scheduledAt, and createdAt. Encounter numbers use OP-
followed by the compact server UUID. Duplicate numbers fail atomically under
the existing database uniqueness constraint.

200 ENCOUNTER_REPLAYED returns the original registration header for the same
actor, key, and canonical payload. Current STAFF authentication and active
patient access still apply. Doctor eligibility is rechecked on new registration
only. Replay describes the original registration, including its initial
scheduled state. SHA-256 fingerprints include the patient
and doctor UUIDs, trimmed complaint and normalized scheduling instant. Missing
schedule is a distinct walk-in value. Keys persist for the synthetic dataset
lifetime. There is no automatic cleanup.

| Status | Examples |
| --- | --- |
| 400 | Missing or invalid key, unsupported request fields, invalid schedule |
| 404 | Patient missing or inactive, or doctor missing |
| 409 | Different payload for the same key, open same-day visit, unavailable profile, missing/inactive/wrong-role account, or a busy registration |
| 500 | Required database write failed |
| 502 / 503 | Invalid eligibility response / unavailable eligibility dependency |

A different key is a new command. If the patient already has a scheduled,
arrived, or in-progress encounter on the requested clinic day, the command
returns ENCOUNTER_OPEN_VISIT. The patient row lock serializes these checks.
The command leaves existing encounters intact. A new date, or a same-day visit
after earlier encounters close, remains possible.

The initial status history has no previous state and records scheduled with
the authenticated actor. The create audit records the same actor and encounter.
The encounter, key, history, and audit commit atomically. The account lookup
happens before database locks. A User-side role or activation change between
lookup and commit remains possible. Operational logs carry safe event metadata.

## Isolated verification

Run the Hospital Maven Wrapper with CLINICAL_ENCOUNTER_TESTS=true and a
dedicated clinical_encounter_test database, using the existing
CLINICAL_ENCOUNTER_JDBC_URL, CLINICAL_ENCOUNTER_DB_USER and
CLINICAL_ENCOUNTER_DB_PASSWORD settings. Enable CLINICAL_DATABASE_TESTS
with its existing CLINICAL_TEST settings and a fresh clinical_schema_test
database. Set CLINICAL_UPGRADE_JDBC_URL to a second fresh database named
clinical_upgrade_test to verify the forward upgrade. Both migration tests
use CLINICAL_TEST_DB_USER and CLINICAL_TEST_DB_PASSWORD.

For cross-service smoke verification, use isolated User, JWKS, MySQL and
PostgreSQL; link a synthetic Doctor profile to an activated role-4 account.
Use an activated STAFF login to select the doctor, register a visit, replay
the same key, and submit a conflicting payload. Check the resulting encounter,
history and audit counts in PostgreSQL.
