# Hospital Management API

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
`jdbc:postgresql://localhost:5432/hospital_db`. Passwords have no defaults.
Hikari defaults to 2–10 connections and a 30-second acquisition timeout.

Flyway's migration source is `src/main/resources/db/migration/`, deliberately
empty. No fake migration or Hospital schema is provided. Compose's Flyway job
mounts this source before startup. Spring Flyway validates/applies the packaged
migrations for standalone runs. Hibernate uses `validate`, SQL initialization is
disabled, and Flyway clean is disabled. Add reviewed migrations when entities exist.

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
configured PostgreSQL service. No test starts Docker.

Structured JSON logging, bounded spools, correlation, privacy filtering, private
Prometheus metrics and W3C tracing remain enabled through existing configuration.
