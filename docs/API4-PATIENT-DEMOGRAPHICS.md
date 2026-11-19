# PAT-003 · Verify patient demographics

`GET /api/v1/hospital/patients/{id}` opens the demographic header of an
active chart before staff use the patient's UUID for a clinical workflow.
Supply a valid User Service bearer token and a patient UUID.

Spring Security authorizes the STAFF role to read active patient
demographics for registration and administrative work. The current rule
applies to all active patient records. Existing patient search remains
ADMIN-only, so staff need a patient UUID from an authorized workflow.

DOCTOR, PATIENT, ADMIN and USER receive `403`. The encounter and doctor
models store assignments, but Hospital has no doctor-to-chart authorization
policy for this route. The patient model has an optional external user ID,
but account linking and patient self-service are not implemented. ADMIN
and USER have no explicit clinical read permission for PAT-003.

Hospital now interprets the User Service role numbers as USER=1,
PATIENT=2, STAFF=3, DOCTOR=4 and ADMIN=5. Deploy with the matching User
Service role contract and obtain fresh access tokens before validating
existing admin workflows.

On success, the standard envelope contains `id`,
`medicalRecordNumber`, `givenName`, `familyName`, `dateOfBirth`,
`sexAtBirth`, and `updatedAt`. Null fields are omitted. The response
uses `Cache-Control: no-store`.

| Status | Meaning |
| --- | --- |
| 400 | Invalid patient UUID |
| 401 | Missing or invalid authentication, including an invalid actor UUID |
| 403 | Caller lacks the STAFF role |
| 404 | Patient is missing or inactive |
| 500 | Persistence failure, including `PATIENT_ACCESS_LOG_FAILED` if the access event cannot be written |

The service writes one allowed `view` event with actor UUID, patient
UUID, server-generated audit correlation UUID and purpose
`patient_identity_verification`. The read and audit insert share one
transaction. Successful data return requires commit. Start and
committed completion are logged at INFO with no patient fields.

The OpenAPI operation is generated from the controller. Run the
contract and authorization tests with:

```powershell
mvn '-Dtest=PatientDemographicsControllerTest,JwtAuthenticationTest,OpenApiDocumentationTest' test
```
