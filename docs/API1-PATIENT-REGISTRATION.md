# API 1: patient registration

Implemented endpoint: `POST /api/v1/hospital/patients`.
See [the learning plan](API-LEARNING-PLAN.md) and [database reference](DATABASE.md)
for the wider design. [Patient search](API2-PATIENT-SEARCH.md) is also implemented;
encounter status changes remain later work.

## Request and authentication

Send `Content-Type: application/json` and an existing valid ADMIN bearer token.
The current User Service contract maps numeric role 5 to ROLE_ADMIN.
USER tokens receive 403 for this POST route. Other existing routes retain their
previous authorization rules. Token issuer, algorithm, JWKS, and internal service
authentication have not changed.

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
    },
    {
      "contactType": "emergency",
      "contactName": "Example contact",
      "relationship": "family",
      "email": "example@example.invalid"
    }
  ]
}
```

MRN is trimmed, required and limited to 40 characters; its case is preserved.
Given name is required and limited to 100 characters. Family name, birth date,
sex at birth and contacts are optional. Future birth dates are rejected.
Null/omitted contacts become an empty list; up to five contacts are allowed.
Null list entries are invalid. isPrimary defaults to false when omitted or null.

Every contact needs a nonblank phone, email or address. Emergency and guardian
contacts need a nonblank contact name. At most one primary contact per contact
type is accepted; a primary self contact and a primary emergency contact are valid
together. Email format and text limits are validated before persistence.

No patient ID, external user link, audit actor, timestamp or soft-delete field is
accepted as an assignable registration property. A patient does not need a User
Service account. The audit actor comes only from the authenticated JWT subject.

## Typed enums

| Java enum      | JSON / PostgreSQL values                                      |
|----------------|---------------------------------------------------------------|
| SexAtBirth     | female, male, intersex, unknown                               |
| ContactType    | self, emergency, guardian                                     |
| AuditActorType | user, service, system                                         |
| AuditOperation | create, update, sign, cancel, correct, supersede, soft_delete |

DTOs and entities use typed Java enums. `@JsonValue` preserves lowercase JSON
values. JPA 3.2 `@EnumeratedValue` with `@Enumerated(EnumType.STRING)` preserves
the existing lowercase varchar values and CHECK constraints. No PostgreSQL native
enum type or migration is introduced. Unsupported input values produce HTTP 400.
Sex at birth is the existing demographic field; gender identity is not added.

## Response and errors

Success is HTTP **201**, using the existing APIResponse envelope:

```json
{
  "success": true,
  "code": "PATIENT_REGISTERED",
  "message": "Patient registered successfully",
  "data": {
    "id": "11111111-1111-4111-8111-111111111111",
    "medicalRecordNumber": "LEARN-0001",
    "givenName": "An",
    "familyName": "Example",
    "dateOfBirth": "1995-06-15",
    "sexAtBirth": "female",
    "createdAt": "2026-11-17T00:00:00Z",
    "contactIds": [
      "22222222-2222-4222-8222-222222222222",
      "33333333-3333-4333-8333-333333333333"
    ]
  },
  "meta": {
    "timestamp": "2026-11-17T00:00:00Z"
  }
}
```

UUIDs and timestamps are illustrative; contactIds contains one UUID for each
saved contact. Existing correlation/trace metadata is included when available.
Null response fields are omitted. No JPA entity is serialized into the response.

| HTTP | Code / meaning                                                                     |
|------|------------------------------------------------------------------------------------|
| 400  | VALIDATION_ERROR: Bean Validation failure with field/message details               |
| 400  | INVALID_REQUEST: malformed JSON or unrelated database integrity failure            |
| 400  | INVALID_ENUM_VALUE: invalid enum with field name and permitted wire values         |
| 400  | CONTACT_DETAILS_REQUIRED: contact has no usable communication/address value        |
| 400  | CONTACT_NAME_REQUIRED: emergency/guardian contact lacks a name                     |
| 400  | DUPLICATE_PRIMARY_CONTACT: two primary contacts share a type                       |
| 401  | Missing/invalid token; INVALID_ACTOR_ID for an unusable authenticated UUID subject |
| 403  | Authenticated caller lacks ADMIN authority                                         |
| 409  | PATIENT_ALREADY_EXISTS: MRN is already reserved, including after soft deletion     |

Security-filter 401/403 responses retain existing behavior; the application does
not replace the existing JWT entry point with a new response contract. Error
responses do not include SQL text, duplicate row contents, keys, or tokens.

## Implementation flow

1. PatientController validates/deserializes the request and passes the subject
   from JwtAuthenticationToken to the service as a string.
2. PatientServiceImpl parses the authenticated subject into an actor UUID and
   validates the cross-field contact rules. Invalid actor identities return 401
   before any writes. The service has no dependency on Spring Security objects. Service-level
   Bean Validation also protects direct calls through the Spring service proxy.
3. One transaction saves and flushes the patient, saves its contacts, then saves and flushes
   one audit event. The database UNIQUE constraint is the authority for MRN
   conflicts, including simultaneous requests. A conflict exits the transaction
   immediately and is translated to 409; no writes continue after SQL failure.
4. An audit event stores patient/entity UUIDs, authenticated actor UUID,
   actor_type=user, operation=create, entity_type=patients. It contains no names,
   contacts or request payload. All three types of rows roll back on any failure.
5. Generated patient/contact UUIDs and database-generated createdAt are returned.

Patient and contact timestamps remain owned by PostgreSQL and inherited through
TimestampedEntity. The immutable audit's occurred_at uses the PostgreSQL default
and is available when the record is read back. It is not configured as a Hibernate
auto-fetched generated property: that combination with immutable entities fails
on insertion in the installed Hibernate version. No timestamp callback or
application-owned substitute was added for registration. The shared audit mapping
uses DynamicInsert to preserve this default when occurredAt is omitted; encounter
status changes explicitly supply their shared workflow instant instead.

No Flyway files are changed. Hibernate validates the mapped patient/contact/audit
subset against the existing schema. Remaining tables are still managed by Flyway.

## Verification

Fast HTTP contract, authorization, DTO JSON and validation checks:

```powershell
mvn -pl springboot-hospital-api '-Dtest=PatientControllerTest,RegisterPatientRequestTest' test
```

Full module suite without a database:

```powershell
mvn -pl springboot-hospital-api test
```

For real PostgreSQL tests, provision a dedicated disposable database named
`clinical_registration_test`, then supply its isolated credentials:

```powershell
$env:CLINICAL_REGISTRATION_TESTS = 'true'
$env:CLINICAL_REGISTRATION_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55440/clinical_registration_test'
$env:CLINICAL_REGISTRATION_DB_USER = '<isolated test role>'
$env:CLINICAL_REGISTRATION_DB_PASSWORD = '<isolated test password>'
mvn -pl springboot-hospital-api -Dtest=PatientRegistrationIntegrationTest test
```

Tests apply Flyway and Hibernate validation, then exercise the real controller,
service, repositories and PostgreSQL transactions through MockMvc. Authentication
helpers supply test JWTs; the separate existing JWT tests verify signing/JWKS
behavior. No HTTP server or external User/JWKS calls are required for registration.

Integration checks cover patient-only registration, contact linkage, lowercase
enum persistence/JSON, audit metadata, database timestamps, contact validation,
direct-service validation, reserved soft-deleted MRNs, rollback after contact or
audit INSERT failure, and two concurrent requests yielding one 201 and one 409.
Test-only failure-injection triggers are installed exclusively in that dedicated
database. Tests never reset schemas, delete clinical rows, or start Docker.
Synthetic fixtures remain until the disposable database is removed by its owner.

During implementation these checks ran in an explicitly authorized isolated
PostgreSQL 17.6 container, with the developer's Compose environment untouched.
The full module run passed: 152 tests, zero failures/errors, and four expected
opt-in migration/startup skips. This includes all eight real-PostgreSQL
registration tests, nine DTO tests, five controller tests, and OpenAPI/security
regressions. Maven packaging also succeeds. The isolated container and its
temporary volume are removed after verification; no development data is reset.
