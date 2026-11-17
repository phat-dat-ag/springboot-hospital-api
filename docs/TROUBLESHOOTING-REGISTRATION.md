# Registration validation diagnostics

## Investigated request

Trace `fdfa8bfff8bedc222ccc0ba2179d4e74` was found in the running OpenSearch index
`springboot-hospital-2026.11.17-logs` at `2026-11-17T09:11:17.685Z`.
Its correlation ID is `0b986534-3679-4e4d-8baa-49e2d02b7bac`.

The single completion event records POST `/api/v1/hospital/patients`, HTTP 400,
errorCode=INVALID_REQUEST, error.category=client, message="API request rejected".
Ingestion worked. The old HTTP message-conversion handler deliberately returned
no parser details and recorded only the generic code, so OpenSearch had no field
or failure reason to show.

The submitted sexAtBirth did not match the enum. Supported values are female,
male, intersex and unknown. Use `"sexAtBirth": "male"` for the intended value.
The enum is parsed before controller/service invocation, so registration and
database writes do not run for this invalid request. A primary emergency contact
and a primary self contact are permitted together; their types differ.

No bearer token, patient payload or patient identity is retained in this guide.
The investigation read the existing log and used synthetic tests, without
replaying the supplied token or registering a patient in the development DB.

## Improved response

Invalid enum input now returns HTTP 400 with the existing response envelope:

```json
{
  "success": false,
  "code": "INVALID_ENUM_VALUE",
  "message": "Request contains an invalid enum value",
  "error": {
    "details": [
      {
        "field": "sexAtBirth",
        "code": "INVALID_ENUM_VALUE",
        "message": "Must be one of: female, male, intersex, unknown"
      }
    ]
  }
}
```

The real response also includes timestamp/correlation/trace metadata. Nested
contact enum errors identify `contacts[0].contactType`. Malformed JSON and
non-enum conversion failures keep the generic INVALID_REQUEST behavior.

## Improved completion log

One WARN completion event remains the owner of the error diagnostic. It includes:

```json
{
  "errorCode": "INVALID_ENUM_VALUE",
  "validation.field": "sexAtBirth",
  "validation.allowed_values": ["female", "male", "intersex", "unknown"]
}
```

The values come from server enum serialization, including lowercase @JsonValue
values. Field paths are accepted only from declared Java record components;
user-defined map keys fall back to `request`. Rejected values, request bodies,
authorization headers and raw parser exception messages are never included.
Expected enum failures do not acquire stack traces or extra duplicate log events.

## Find the request in OpenSearch

Local bindings inspected for this investigation are OpenSearch API 9277 and
Dashboards 5677. In Discover, use the existing application log index pattern
covering `springboot-hospital-*-logs`, and search:

```text
service: "hospital-api" and traceId: "fdfa8bfff8bedc222ccc0ba2179d4e74"
```

For historical events, set an absolute time range around 09:11 UTC on 2026-11-17
(16:11 Asia/Bangkok). Useful columns are traceId, correlationId, errorCode,
http.response.status_code, validation.field and validation.allowed_values.
Refresh the index-pattern field list if new fields are not yet selectable.

Source changes require rebuilding/restarting only the Hospital API before new
requests produce these diagnostics. With its dependencies already running:

```powershell
docker compose up -d --build --no-deps hospital-api
```

This command was not executed during investigation. OpenSearch does not need
restarting. Existing indexed events keep their original generic fields; query
the trace/correlation ID from a new response after rebuilding to see new details.

A separate regression discovered by tests was also corrected: BaseController's
created helper now returns HTTP 201 rather than 200.

Verification: `mvn -pl springboot-hospital-api test -B -ntp` passed with 161 tests,
zero failures/errors, and 12 expected database opt-in skips. Tests cover enum
response details, nested field paths, one safe completion event, private-value
exclusion, malformed JSON fallback, and the existing logging/security contracts.
No PostgreSQL container was started or production registration replayed for this fix.
