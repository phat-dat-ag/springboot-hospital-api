# P04 encounter vital signs and assessment notes

Use a current DOCTOR bearer for an encounter assigned to that doctor's active
Hospital profile. Hospital verifies the current linked User account for every
request. Other roles receive 403. A missing or invalid bearer receives 401.
Missing or out-of-scope encounters and notes return 404. Inactive patient
records are outside scope.

New clinical writes require an IN_PROGRESS encounter and current clinic-date
doctor eligibility. The clinic timezone remains configurable and defaults to
Asia/Ho_Chi_Minh. Authorized reads do not require a current license expiration
check. All successful responses use APIResponse and Cache-Control: no-store.

## Endpoints

Paths below are relative to /api/v1/hospital/encounters/{id}.

| Method and path | Success | Data |
| --- | --- | --- |
| POST /vitals | 201 | Recorded vital set, measurement time, created time and ID |
| GET /vitals | 200 | Bounded list of vital sets |
| POST /notes | 201 | New assessment draft with revision |
| GET /notes | 200 | Bounded list of note summaries without bodies |
| GET /notes/{noteId} | 200 | Note body, author, type, status, timestamps and revision |
| PATCH /notes/{noteId} | 200 | Updated draft and new revision |
| POST /notes/{noteId}/signature | 200 | Signed note and signature timestamp |

Lists take page (zero-based, default 0) and size (1–50, default 20). Results
contain items, page, size and hasNext. Vitals sort by measuredAt descending,
and notes by createdAt descending, both with a descending UUID tie-breaker.
Concurrent inserts can shift rows between offset pages.

## Record vitals

Send a measurements object with at least one value. Supply measuredAt as an
ISO instant when known, or omit it to use the server current time. Future
measurement timestamps are rejected. PostgreSQL stores timestamps as instants.

~~~json
{"measurements":{"heartRateBpm":72,"oxygenSaturationPct":98}}
~~~

The fixed units are Celsius (temperatureC), mmHg (systolicBpMmhg and
diastolicBpMmhg), beats per minute (heartRateBpm), breaths per minute
(respiratoryRatePerMin), percent (oxygenSaturationPct), kilograms (weightKg),
and centimeters (heightCm). Numeric precision and range follow the existing
database constraints. The recorder is derived from the JWT. Corrections,
deletion and delegation to STAFF are outside this slice.

## Draft, review, and sign

Create a draft with a nonblank content string up to 10,000 characters. The
server sets type assessment, author and draft status. On edit, send both
content and expectedRevision. To sign, send only expectedRevision from the
exact draft response the doctor reviewed. The opaque revision is derived
from the persisted note ID, update instant, status and content.

~~~json
{"content":"Synthetic assessment text"}
~~~

A stale revision, attempted edit of a signed note, repeated signature, or
write to an encounter outside IN_PROGRESS returns 409. A caller cannot edit
or sign a different doctor's draft. Drafts are visible only to their author
while assigned. Signed notes are visible to the assigned doctor. Notes
entered in error are excluded. PostgreSQL's existing trigger protects signed
content. To keep the revision check atomic, writes lock the encounter, patient,
doctor and target note where applicable. Edit and sign cannot both succeed
against the same revision.

Read access writes a purpose-specific access audit. Vitals, note edits and
signatures commit with their clinical write audit or roll back together.
Required audit failures return 500. User verification timeouts return 503 and
malformed responses return 502. Dependency verification happens before row
locks and remains a point-in-time check. Requests, note text and measured
values are excluded from ordinary operational logs.

No migration is required. P04 uses the existing vital_signs and clinical_notes
tables and triggers. Diagnosis and encounter completion remain in P05.

## Verification

The Hospital Maven Wrapper passed 339 tests against the full regression suite
with PostgreSQL 17.6 enabled. Coverage included assigned-doctor access, invalid
measurements, stale revisions, an edit/sign race, and rollback on audit failure.
A live isolated HTTP run verified recording vitals, editing and signing the
reviewed revision, STAFF denial, persisted audits, and operational log privacy.
