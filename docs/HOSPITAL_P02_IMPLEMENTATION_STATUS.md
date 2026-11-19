# P02 doctor selection and encounter registration

P02 implements both STAFF endpoints and PostgreSQL-backed registration replay.
It uses the existing internal User role contract and Hospital clinic clock.
See the [HTTP contract](API5-OUTPATIENT-REGISTRATION.md) for payload examples and
policy details. Reception queue, reassignment, and new transitions remain in P03
or later.

## Verification

The Hospital Maven Wrapper passed 330 tests with no failures or skips.
Isolated PostgreSQL 17.6 verified clean migration, upgrade from V9, the
deferred foreign key, concurrent registration, and rollback on history and
audit failures. The live demo used User Service, JWKS, MySQL, Hospital and
PostgreSQL. It verified current account activation and role, dependency
outage behavior, replay after Hospital restart, and exactly one stored
encounter, history, audit, and idempotency key.

## Design

- Doctor search filters linked, non-deleted profiles and license expiry locally
  in one query. One bounded candidate window is verified against current User
  records. There is no role mirror or eligibility cache.
- Registration queries eligibility before acquiring PostgreSQL locks. It checks
  the patient first, then rechecks the doctor profile and verified account link
  under locks. User role and activation are point-in-time checks across services.
- The parent-owned V10 migration adds actor-scoped keys and a deferred encounter
  foreign key. PostgreSQL commits the encounter, initial history, clinical audit,
  and key together.
- A replay discloses the original registration header to authenticated STAFF
  using the same actor key, subject to active-patient access. It performs no
  clinical write and does not requalify the historic doctor assignment.
- An open visit for the requested clinic day yields a 409 with no merge or
  partial write. Other dates and same-day visits after prior visits close are
  evaluated independently.

[User lookup client](../src/main/java/com/hospitalmanagement/hospital/client/UserServiceClient.java),
[V10](../../db/migration/hospital/V10__encounter_registration_idempotency.sql),
[Readiness roadmap](HOSPITAL_OUTPATIENT_DEMO_READINESS.md#p-prioritized-implementation-roadmap)
