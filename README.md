# Hospital Management API

For a guided next phase, follow the [three-API learning plan](docs/API-LEARNING-PLAN.md)
for patient registration, patient search and encounter status changes.

This module is an infrastructure foundation. It contains no clinical or billing
entities, repositories, seed data, scheduled jobs, or business operations.

Entry point: `com.hospitalmanagement.hospital.HospitalApiApplication`.
Coordinates: `com.hospitalmanagement:hospital-api:0.0.1-SNAPSHOT`.

| GET route | Purpose | Permission |
| --- | --- | --- |
| `/api/v1/hospital/technical/authentication` | Confirm subject, roles and issuer without exposing tokens | USER or ADMIN |
| `/api/v1/hospital/technical/users/{id}` | Exercise the existing internal User client | USER or ADMIN |
| `/api/v1/hospital/technical/jwks` | Inspect User public keys via the existing client | USER or ADMIN |
| `/actuator/health`, `/actuator/health/readiness` | Health and readiness | Public |
| `/v3/api-docs`, `/swagger-ui/index.html` | OpenAPI | Public |

User lookup retains the existing UUID lookup behavior; it does not enforce record
ownership. Internal requests carry freshly signed service JWTs, never forwarded
user tokens. Verification retains RS256, issuer, timestamps, required claims and
numeric role mapping. All other incoming routes require authentication.

## PostgreSQL and Flyway

Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD`. Outside Compose, the JDBC URL is
`jdbc:postgresql://localhost:5432/clinical_db`. Passwords have no defaults.
Hikari defaults to 2–10 connections and a 30-second acquisition timeout.

Flyway's single migration source is the root `db/migration/hospital/` directory.
Maven copies it into `db/migration` in the jar, and Compose mounts it read-only
in the Flyway runner. Build this Dockerfile from the repository root (Compose
already does this). Hibernate uses `validate`, SQL initialization is disabled,
and Flyway clean is disabled. There are no JPA entities yet, so validation does
not compare mappings to these tables; Flyway and database tests verify them.

The nine migrations create all 28 Clinical business tables without seed data.
See [the complete database reference](docs/DATABASE.md) for every table,
relationship, lifecycle, timestamp policy, migration and verification command.
The module/application/package retain their intermediate Hospital names;
Clinical module renaming is a separate phase. PostgreSQL is `clinical_db`.

## Protected authentication contracts

The unchanged JWKS publisher requires retaining issuer/subject `learning-service`,
kid `learning-rsa-v1`, owner `LEARNING`, and `/api/v1/jwks/learning` for internal
User authentication. Existing key material, filenames and mount paths remain.
These identify a wire contract, not the new application's business domain.

Configure `HOSPITAL_INTERNAL_JWT_ENABLED`, `HOSPITAL_INTERNAL_JWT_ISSUER`,
`HOSPITAL_INTERNAL_JWT_AUDIENCE`, `HOSPITAL_INTERNAL_JWT_TTL`, and the indexed
`APP_SECURITY_SIGNING_KEYS_*` settings. Signing and User client attachment are
enabled in Compose. Standalone runs must enable signing together with
`USER_SERVICE_INTERNAL_JWT_ENABLED`. See [INTERNAL-JWT.md](INTERNAL-JWT.md).

## Verification

Use Java 17 or later. Run `mvn test` here or at the root for User and Hospital.
Spring does not load `.env` automatically; inject settings through the IDE/shell.
The normal suite uses local HTTP fixtures and excludes PostgreSQL from web startup
tests. The opt-in database test requires `HOSPITAL_DATABASE_TESTS=true` and a real,
configured PostgreSQL service. No test starts Docker. The pure database suite is separately enabled with
`CLINICAL_DATABASE_TESTS=true`; see the database reference for isolated credentials.

Structured JSON logging, bounded spools, correlation, privacy filtering, private
Prometheus metrics and W3C tracing remain enabled through existing configuration.
