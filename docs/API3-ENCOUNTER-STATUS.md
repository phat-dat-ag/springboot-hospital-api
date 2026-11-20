# ENC-001 encounter status changes

PATCH /api/v1/hospital/encounters/{id}/status accepts a User-issued bearer JWT
and an expected state. Send the exact current state supplied by an encounter
read. The server supplies the event instant.

| Expected state | Target state | Actor |
| --- | --- | --- |
| scheduled | arrived | STAFF |
| scheduled | cancelled | STAFF |
| arrived | cancelled | STAFF |
| arrived | in_progress | Currently assigned DOCTOR |

ADMIN, USER and PATIENT receive 403. STAFF and DOCTOR receive 403 when they
request an action assigned to the other role. All completion requests fail.
An admitted caller receives 409 for a recognized but unsupported target,
including completed. Cancelled, completed and entered-in-error records stay
unchanged. Cancellation after examination starts is forbidden.

~~~http
PATCH /api/v1/hospital/encounters/11111111-1111-4111-8111-111111111111/status
Authorization: Bearer <STAFF access token>
Content-Type: application/json

{"expectedStatus":"scheduled","status":"arrived"}
~~~

To cancel, send status cancelled and a nonblank reason of at most 1,000
characters. Leading and trailing whitespace are trimmed. To start, send
expectedStatus arrived and status in_progress with the assigned doctor's
access token.

A successful 200 retains the existing APIResponse data fields id, patientId,
status, startedAt, endedAt and updatedAt. Nullable timestamps are omitted
under the existing serialization configuration. The [workflow detail
endpoint](API6-ENCOUNTER-WORKFLOW.md) exposes arrivedAt from the committed
arrival history, with no separate arrival column.

| Status | Meaning |
| --- | --- |
| 400 | Malformed UUID/body, validation failure, or required cancellation reason missing |
| 401 | Missing or invalid authentication or unusable JWT subject |
| 403 | Disallowed role or action, or unusable doctor identity |
| 404 | Encounter absent or outside the verified doctor's assignment |
| 409 | Expected state changed, transition disallowed, patient inactive, doctor license ineligible, or lock busy |
| 500 | Persistence failed and the change was rolled back |
| 502 / 503 | Invalid User verification response / User verification unavailable |

Before taking row locks, Hospital maps the authenticated User UUID to an
active doctor profile and asks User Service whether the account is currently
active with role DOCTOR. The profile and assignment are rechecked under lock.
Starting an examination requires a valid clinic-day license when expiry is
recorded. Missing expiry is accepted. Locks are taken in encounter, patient,
then doctor order. The status, history and clinical audit share one transaction.
Failure rolls back all writes. Arrival is recorded in history, and examination
start also sets startedAt. Operational logs record a successful transition
after commit and omit patient/encounter IDs and free text. User account
eligibility remains a point-in-time check.

See the [P03 workflow contract](API6-ENCOUNTER-WORKFLOW.md) for the queues,
detail fields, and test instructions.
