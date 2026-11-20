# P06 patient visit and encounter status history

Use the existing DOCTOR bearer scheme in Swagger UI. Hospital verifies the
current User account and resolves its linked active doctor profile. Other
roles receive 403. Missing or invalid authentication receives 401. Reads
use the existing APIResponse envelope and Cache-Control: no-store.

## Patient visit history

GET /api/v1/hospital/patients/{id}/encounters?size=20

The patient must be active and have at least one completed encounter assigned
to the caller. A missing patient or an unavailable care relationship returns
404. The response data contains a limited patient name and UUID header, items,
and nextCursor when there are more results. Each item includes an operational
encounter summary and createdAt. Visits are sorted newest first by createdAt
then UUID. Only completed visits currently assigned to the doctor appear.
Patient contacts, MRN, chief complaint and cancellation reason are excluded.

## Encounter status history

GET /api/v1/hospital/encounters/{id}/status-history?size=20

Only the currently assigned doctor can read the chronology. Each item
contains the status event UUID, previous status when present, new status,
and event instant. Actor identities and free-text reasons are excluded.
Events are ordered oldest first by event time then UUID.

## Pagination and clinical detail

Both endpoints accept size from 1 through 50 and default to 20. Send the
returned nextCursor unchanged as the cursor query parameter for the next page.
Omit cursor on the first request. Malformed cursors return 400. An absent
nextCursor indicates the last page. Scope is checked on every request.

Use the existing encounter detail, vitals, notes, and diagnoses routes for
a selected visit. The GET notes list and detail expose signed notes only on
completed encounters. For a selected visit, use GET
/api/v1/hospital/encounters/{id} with the assigned DOCTOR token to read the
optional data.clinicalConclusion. The list and STAFF workflow reads omit it.
The signed assessment remains on the notes route. Older completed visits may
have no conclusion. Both the visit list and detail are audited.

The patient header and each disclosed visit receive access audit records,
including the header when a valid page has no items. Status history audits
the encounter read. Required audit failures withhold the response.

In Swagger, complete a visit through [ENC-001](API3-ENCOUNTER-STATUS.md).
Call GET /api/v1/hospital/patients/{id}/encounters?size=20 with the patient
UUID. Select the returned items[].encounter.id and open the detail GET.
The new completed visit is eligible for the list as soon as completion commits.
