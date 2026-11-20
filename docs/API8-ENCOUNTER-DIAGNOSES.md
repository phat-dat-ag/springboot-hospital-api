# P05 diagnosis catalog and encounter diagnosis recording

All routes require a current DOCTOR bearer and an active linked doctor
profile. Encounter operations also require current assignment. Successful
responses use APIResponse and Cache-Control: no-store.

| Request | Behavior |
| --- | --- |
| GET /api/v1/hospital/diagnosis-codes | Lists active catalog entries. Optional q is a case-insensitive literal substring of code or description, up to 100 characters. |
| GET /api/v1/hospital/encounters/{id}/diagnoses | Lists active diagnoses for an assigned encounter, primary first. Records an access audit before returning. |
| POST /api/v1/hospital/encounters/{id}/diagnoses | Records a diagnosis for an assigned IN_PROGRESS encounter with an active catalog code. Returns 201. |

Both GET routes accept zero-based page (default 0) and size (1–50, default 20).
The response includes items, page, size, and hasNext. Copy the catalog
entry id into diagnosisCodeId when recording a diagnosis.

```json
{
  "diagnosisCodeId": "<active-catalog-uuid>",
  "primary": true,
  "clinicalDescription": "Synthetic assessment for API testing."
}
```

The doctor and diagnosis timestamp are assigned by the service and database.
The description is optional and limited to 2,000 characters. Primary defaults
to false. The diagnosis, initial history and write audit commit atomically.
The existing database indexes enforce one active primary and one active
instance of the same code per encounter. A duplicate returns 409.

Other roles receive 403. A doctor outside the assignment receives 404,
as does a request for a missing or inactive catalog entry. A write outside
IN_PROGRESS returns 409. A failed required audit withholds data or rolls
back the write.

To test with Swagger, use an existing approved catalog entry or an authorized
synthetic fixture in an isolated database. The public API has no catalog
maintenance operation. Clinical completion remains unavailable until its
assessment and diagnosis requirements are confirmed.
