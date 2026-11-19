# AGENTS.md — Spring Boot Hospital Service

## 1. Purpose and Scope

You are working on `springboot-hospital`, a single Spring Boot application that belongs to a larger parent project.

Your responsibility is to produce secure, maintainable, production-oriented Java code that follows the repository's established architecture, business rules, and coding standards.

All changes must prioritize:

- Correctness and patient safety.
- SOLID principles and Clean Code.
- Clear, descriptive naming.
- Readable, reusable, testable code.
- Appropriate separation of responsibilities.
- Simple, maintainable architecture.
- Secure configuration and deployment.
- Database integrity and transaction consistency.
- Reliable testing and verification.
- Minimal, well-scoped changes.

Do not introduce unnecessary complexity, frameworks, abstractions, or services.

These instructions apply to all tasks performed within this service.

### 1.1 Repository Boundaries

The Hospital application is a single Maven module.

It uses feature-oriented packages, primarily under:

`src/main/java/com/hospitalmanagement/hospital`

The parent project owns certain shared integration resources, including the authoritative Hospital Flyway migrations.

The Hospital service integrates with external User and JWKS services through established contracts.

Rules:

- Respect the boundaries between the Hospital service and the parent project.
- Do not treat the Hospital service as an independent microservices system.
- Do not modify the User Service or shared JWKS submodule without explicit authorization.
- Do not change shared JWT or JWKS contracts implicitly.
- Do not modify parent-owned configuration or database resources without reviewing their ownership and obtaining authorization.
- Preserve backward compatibility unless the task explicitly requires a breaking change.
- Do not change unrelated services to simplify a local implementation.

## 2. Mandatory Pre-Implementation Workflow

Before creating, modifying, refactoring, or reviewing Java code:

1. Read this `AGENTS.md`.
2. **Read `docs/java-code-style.md` in full.**
3. Inspect the relevant existing source files.
4. Inspect related interfaces, DTOs, entities, repositories, and tests.
5. Identify existing reusable components.
6. Understand the relevant business workflow.
7. Inspect applicable database constraints and Flyway migrations.
8. Review authentication, authorization, and audit requirements.
9. Review affected configuration and environment variables.
10. Determine the smallest coherent implementation.
11. Implement using SOLID, Clean Code, and repository conventions.
12. Add or update appropriate tests.
13. Execute relevant verification.
14. Review the final diff for correctness, security, formatting, and scope.

Never begin implementation based only on assumptions about the repository.

For tasks that do not involve Java changes, inspect the relevant files and follow the applicable sections of this document.

If an important requirement is ambiguous, inspect existing conventions and documentation before making a decision.

Do not invent business rules, security permissions, database relationships, or external contracts.

## 3. Mandatory Java Code Style

The authoritative Java formatting document is:

`docs/java-code-style.md`

**Reading this document is mandatory before every Java coding task.**

Follow all applicable rules defined in that document, including R01–R19.

The style guide takes precedence over generic formatting preferences in this file.

Key requirements include:

- Four-space indentation.
- One statement per line.
- Braces for control-flow statements.
- Explicit imports instead of wildcard imports.
- Logical blank lines between code sections.
- Annotations placed on separate lines.
- Readable method declarations and argument lists.
- Clearly formatted multiline boolean expressions.
- Readable Stream pipelines and fluent method chains.
- Consistent wrapping of nested lambdas.
- Descriptive identifiers.
- No unnecessary fully qualified class names in source code.
- No trailing whitespace.

Target approximately 80 characters per line.

Use 100 characters as the normal maximum.

Allow up to 120 characters only for exceptional, difficult-to-split constructs as permitted by the style guide.

Readability takes priority over minimizing line count.

Do not compress complex expressions simply because they fit within 100 characters.

Apply the same standards to production code and test code.

### 3.1 Java Version Compatibility

The audited repository currently has:

- Maven compiler target: Java 17.
- Docker build/runtime: Java 21.
- Coding style documentation oriented toward Java 21.

Use only Java language features supported by the effective Maven compiler configuration.

Do not introduce Java 21-only language features until the compiler target is explicitly upgraded.

Do not modify the Java version, Maven compiler settings, or Docker runtime without authorization.

## 4. SOLID Principles

All implementations must follow SOLID principles where applicable.

### 4.1 Single Responsibility Principle

Each class should have one clearly defined responsibility.

Each method should perform one meaningful operation at its abstraction level.

Avoid:

- Controllers containing business logic.
- Services containing unrelated workflows.
- Repositories implementing business decisions.
- Utility classes containing unrelated operations.
- Classes responsible for validation, persistence, security, mapping, and logging simultaneously.

A service may coordinate several operations when they belong to the same business workflow.

### 4.2 Open/Closed Principle

Prefer designs that allow reasonable extension without repeatedly modifying unrelated existing code.

Use extension points when there is a demonstrated requirement.

Do not introduce speculative strategy patterns, factories, or inheritance hierarchies.

### 4.3 Liskov Substitution Principle

Implementations must preserve the contracts of their abstractions.

Do not create inheritance relationships that change expected behavior or weaken invariants.

Prefer composition over inheritance when appropriate.

### 4.4 Interface Segregation Principle

Keep interfaces focused and cohesive.

Avoid large interfaces containing unrelated operations.

Do not create interfaces for every class automatically.

The existing clinical service interface/implementation convention should be followed where applicable.

### 4.5 Dependency Inversion Principle

Use constructor injection for required dependencies.

Prefer final dependency fields.

Depend on appropriate abstractions when they provide meaningful separation or testability.

Do not instantiate Spring-managed dependencies manually.

Avoid circular dependencies.

Do not introduce an interface solely to satisfy an architectural slogan.

## 5. Clean Code Principles

Follow:

- SOLID.
- DRY.
- KISS.
- YAGNI.
- Separation of Concerns.
- High Cohesion.
- Low Coupling.
- Explicitness over cleverness.
- Composition over unnecessary inheritance.

Code should be understandable without extensive explanatory comments.

Prefer straightforward imperative logic when it is clearer than a complex functional expression.

Do not overengineer simple requirements.

Do not add generic infrastructure for hypothetical future use cases.

## 6. Naming Conventions

Use descriptive, consistent, domain-oriented names.

### 6.1 Classes and Interfaces

Use PascalCase.

Class names must communicate responsibility.

Examples:

- `PatientRegistrationService`
- `PatientRepository`
- `PatientDemographicsResponse`
- `EncounterStatusService`
- `MedicalRecordAccessLog`

Follow existing class naming conventions in the affected feature.

Avoid vague names such as:

- `DataManager`
- `CommonProcessor`
- `GeneralService`
- `Helper`
- `Utils`
- `ObjectHandler`

Generic names are acceptable only when the responsibility is genuinely generic and well-defined.

### 6.2 Methods

Use lowerCamelCase.

Method names should describe observable behavior.

Prefer:

- `registerPatient`
- `validateContacts`
- `findActivePatient`
- `recordMedicalRecordAccess`
- `validateStatusTransition`

Avoid:

- `process`
- `execute`
- `handleData`
- `doSomething`
- `runLogic`

A generic method name may be appropriate when it is part of an established interface contract.

### 6.3 Variables and Fields

Names must explain their purpose.

Prefer:

- `patientId`
- `medicalRecordNumber`
- `primaryContact`
- `requestedStatus`
- `authenticatedUserId`

Avoid meaningless abbreviations and temporary names.

Short identifiers are acceptable in small, obvious scopes.

### 6.4 Boolean Naming

Boolean names should express a clear predicate.

Examples:

- `isActive`
- `hasPermission`
- `requiresApproval`
- `isPrimaryContact`
- `canAccessPatient`

Avoid ambiguous Boolean names.

### 6.5 Constants

Use UPPER_SNAKE_CASE for Java constants.

Extract meaningful domain or technical constants when doing so improves clarity and reuse.

Do not create constants for every literal unnecessarily.

### 6.6 Domain Terminology

Use the same name for the same business concept throughout the application.

Distinguish clearly between:

- Patient UUID.
- Medical Record Number (MRN).
- External User account UUID.
- Encounter UUID.
- Encounter business number.
- Clinical audit event.
- Operational log event.

Do not use these identifiers interchangeably.

## 7. Reusability and Duplication

Before implementing functionality, search for existing suitable components.

Reuse established:

- Validators.
- Response builders.
- Error handling.
- Security utilities.
- Audit components.
- Repository queries.
- Specifications.
- DTO mapping patterns.
- Pagination logic.
- Configuration infrastructure.
- Logging and tracing utilities.

Do not duplicate meaningful business logic.

Extract shared logic when:

1. The behavior is genuinely shared.
2. The abstraction has a clear responsibility.
3. Reuse improves maintainability.
4. The abstraction does not introduce unnecessary coupling.

Do not create a shared utility simply because two unrelated operations contain similar lines.

Keep feature-specific logic within its feature until sharing is justified.

## 8. Method Design and Business Logic

Methods must be cohesive, readable, and appropriately sized.

Requirements:

- One meaningful responsibility per method.
- Clear input and output.
- Explicit business validation.
- Minimal nesting.
- Predictable side effects.
- Descriptive extracted methods.
- Appropriate transaction boundaries.
- No unnecessary mutable state.

Use guard clauses when they simplify control flow.

Separate workflow orchestration from detailed validation and transformation logic when useful.

For complex business operations, the main service method should clearly communicate the workflow.

Do not extract trivial one-line methods without a meaningful abstraction.

Avoid:

- Deeply nested conditions.
- Excessive Boolean flags.
- Long parameter lists.
- Dense Stream chains.
- Nested lambdas with side effects.
- Hidden database operations.
- Methods that mix unrelated responsibilities.

Prefer meaningful private methods over comments describing complex code blocks.

## 9. Package and Layer Organization

Follow the existing feature-oriented architecture.

New clinical functionality should normally be placed within its corresponding domain package.

Typical components include:

- Controller.
- Service interface.
- Service implementation.
- Repository.
- Entity.
- Request DTO.
- Response DTO.
- Enum.
- Domain validation.
- Feature-specific helpers.

Create additional layers only when justified.

### 9.1 Controllers

Controllers must remain thin.

Responsibilities:

- HTTP request binding.
- Bean Validation.
- Authenticated principal extraction.
- Delegation to services.
- Response construction.
- OpenAPI documentation.

Do not put business rules or database access in controllers.

Reuse the existing response infrastructure where applicable.

Preserve intentional exceptions in existing technical integration endpoints.

### 9.2 Services

Services own:

- Business workflows.
- Cross-field validation.
- State transitions.
- Transaction coordination.
- Authorization checks requiring domain data.
- Required audit coordination.
- Application-level error translation.

Avoid God Services.

### 9.3 Repositories

Repositories own persistence access.

Use Spring Data JPA, derived queries, JPQL, Criteria, or native SQL according to the actual requirement.

Do not put business workflows in repositories.

### 9.4 DTOs and Mappers

Use explicit request and response types.

Prefer Java records for suitable immutable transport contracts.

Use dedicated mapping methods when mapping complexity justifies them.

Do not introduce MapStruct or another mapping framework without an established need and authorization.

Never return JPA entities directly from clinical endpoints.

## 10. Lombok and Java Records

Use Lombok selectively.

Preferred patterns include:

- `@Getter` where appropriate.
- `@RequiredArgsConstructor` for constructor injection.
- `@FieldDefaults` where consistent with the feature.
- `@FieldNameConstants` when field-name constants are actually consumed.
- Targeted builders when they improve object construction.

Avoid `@Data` on JPA entities.

Do not automatically generate entity `toString()`, `equals()`, or `hashCode()` without reviewing JPA identity, lazy-loading, and sensitive-data implications.

Do not generate string representations that expose patient information.

Preserve JPA-compatible constructors.

Use explicit mutation methods when they improve domain integrity.

For Java records:

- Prefer immutable components.
- Defensively copy mutable collections when appropriate.
- Preserve validation semantics.
- Avoid redundant Lombok annotations.
- Never log complete patient DTOs or records.

## 11. PostgreSQL, JPA, and Hibernate

The application uses PostgreSQL, Spring Data JPA, Hibernate, and Flyway.

The authoritative Hospital migration files are owned by the parent project under:

`db/migration/hospital`

Inspect the actual migrations before modifying entity mappings or persistence behavior.

Requirements:

- Keep JPA mappings consistent with verified database definitions.
- Preserve primary keys and foreign keys.
- Respect unique and check constraints.
- Respect database triggers and protected lifecycle rules.
- Use appropriate indexes.
- Avoid unnecessary eager loading.
- Prevent N+1 query problems.
- Use bounded pagination for potentially large result sets.
- Use stable ordering for paginated queries.
- Use parameter binding.
- Avoid SQL injection.
- Use database-specific SQL only when justified.
- Preserve database-managed timestamps.
- Do not assume schema validation proves complete behavioral correctness.

Do not enable Hibernate automatic schema creation or destructive updates for production.

### 11.1 Flyway

Use forward-only versioned migrations.

Never edit an already-applied migration.

Never run Flyway clean against persistent clinical data.

Do not delete or reset existing clinical tables.

Review migration compatibility with existing records.

Validate migrations against isolated PostgreSQL environments when changes depend on actual database behavior.

Parent-owned migrations require explicit scope authorization before modification.

## 12. Transactions and Concurrency

Use `@Transactional` at appropriate service boundaries.

Required clinical writes, related histories, and persistent audit records must remain consistent.

Do not split a logically atomic clinical operation into unrelated transactions without a documented reason.

Preserve existing locking and transaction ordering.

For encounter updates, inspect the established locking order before introducing new locks.

Consider:

- Concurrent updates.
- Unique-constraint races.
- Optimistic or pessimistic locking.
- Transaction rollback.
- Idempotency.
- Retry safety.
- Stale state detection.
- Database-generated values.

Do not claim a database write is committed merely because `save()` or `flush()` returned successfully.

Log committed success only after the relevant transaction has committed.

Do not add distributed transactions or complex retry frameworks without a real requirement.

## 13. REST API Standards

Follow the existing Hospital API namespace:

`/api/v1/hospital`

Preserve established endpoint contracts unless a change is explicitly requested.

Use appropriate HTTP methods and status codes.

Requirements:

- Validate request input.
- Use dedicated DTOs.
- Return consistent responses.
- Preserve existing error codes.
- Handle missing resources appropriately.
- Use explicit pagination and sorting.
- Avoid exposing internal exception details.
- Document endpoint authorization.
- Update OpenAPI annotations.
- Avoid returning unnecessary patient fields.
- Prevent sensitive response caching where required.

Reuse existing components such as:

- `APIResponse`
- `APIMeta`
- `APIError`
- `BaseController`
- `BusinessException`
- `ErrorCode`
- `GlobalExceptionHandler`

Do not introduce a second response envelope or global exception framework.

## 14. Validation and Exception Handling

Apply validation at the correct layer.

### Request validation

Use Bean Validation for transport-level constraints.

### Business validation

Use services for cross-field, lifecycle, and domain-specific rules.

### Database validation

Use PostgreSQL constraints for persistent integrity.

Do not rely exclusively on application validation when concurrency can violate assumptions.

Use established business exceptions for expected failures.

Map known conflicts deliberately.

Do not expose raw SQL exceptions, database constraint details, stack traces, or sensitive submitted values to API clients.

Avoid catching broad exceptions unless the application can handle them meaningfully.

Do not suppress errors silently.

## 15. Authentication and Authorization

Reuse the existing Spring Security infrastructure.

The audited JWT role mapping includes:

- USER.
- PATIENT.
- STAFF.
- DOCTOR.
- ADMIN.

These roles are not automatically hierarchical.

Do not invent role inheritance.

Do not modify JWT issuers, claims, verification algorithms, signing keys, or JWKS contracts without explicit authorization.

### 15.1 Endpoint Authorization

Every new endpoint must have an explicit, reviewed access policy.

Do not rely blindly on a general authenticated fallback.

Test both authorized and unauthorized access.

Do not introduce hardcoded staff-to-patient UUID allowlists.

Do not assume that STAFF, DOCTOR, PATIENT, or ADMIN automatically has unrestricted access to every clinical record.

Follow the approved business authorization model.

Where a relationship-based authorization policy has not yet been implemented, preserve secure denial rather than inventing access rules.

### 15.2 Existing PAT-003 Behavior

The audited PAT-003 endpoint currently permits STAFF to retrieve limited demographics for active patients.

It does not use static UUID grants.

Do not silently broaden this behavior to other roles or clinical data.

Changes require explicit authorization requirements and regression tests.

## 16. Healthcare Data Protection

Treat patient and clinical information as sensitive.

Apply data minimization to:

- API responses.
- Operational logs.
- Tracing.
- Metrics.
- Exceptions.
- Diagnostic endpoints.
- Test fixtures.
- Documentation.

Do not expose patient names, dates of birth, addresses, phone numbers, diagnoses, prescriptions, laboratory results, or other sensitive values without an approved business requirement.

Use synthetic data in tests.

Never commit real patient data.

Do not log entire entities, DTOs, HTTP bodies, authorization headers, or JWTs.

Clinical corrections must preserve required history and accountability.

Do not bypass database protections against destructive changes to clinical records.

## 17. Clinical Audit Requirements

Distinguish operational logging from persistent clinical auditing.

Operational logs are not a substitute for clinical audit records.

For applicable operations, record:

- Authenticated actor.
- Target resource.
- Operation.
- Event time.
- Required controlled metadata.
- Relevant audit correlation information.

Derive actor identity from validated authentication.

Do not trust caller-supplied actor IDs without an approved contract.

Preserve required access auditing before disclosing protected records.

If required audit persistence fails, do not return protected data as though the access was successfully audited.

Keep audit writes transactionally consistent with business operations where required.

Do not modify append-only clinical audit history.

## 18. Logging and Observability

Reuse the existing structured logging and observability infrastructure.

Preserve:

- Correlation IDs.
- Trace IDs.
- Span IDs.
- Structured JSON events.
- Existing request logging.
- Micrometer metrics.
- OpenTelemetry tracing.
- Protected Prometheus exposure.

Use meaningful event names.

Choose log levels appropriately:

- DEBUG: detailed development diagnostics.
- INFO: normal significant workflow events.
- WARN: expected abnormal conditions requiring attention.
- ERROR: unexpected technical failures.

Avoid duplicate logging.

Never log sensitive patient information or secrets.

Do not interpolate raw database exception messages into ordinary log strings.

Do not add patient IDs, user IDs, request parameters, or other high-cardinality identifiers to metric labels.

Do not add sensitive values to tracing attributes.

Verify the final encoded JSON output when changing privacy-sensitive logging behavior.

## 19. Environment and Configuration Management

Configuration must be consistent, secure, and suitable for production.

The project must maintain appropriate environment configuration and examples.

Relevant files include:

- `.env`
- `.env.example`
- `application.properties`
- Applicable profile-specific configuration.
- Parent Docker Compose configuration.

### 19.1 Environment Synchronization

Whenever an environment variable is introduced, renamed, or removed:

1. Update the relevant Spring Boot configuration.
2. Update `.env.example`.
3. Update the applicable local `.env` when it exists and can be safely updated without replacing existing values.
4. Update Docker Compose mappings when required.
5. Update configuration documentation when necessary.
6. Verify the variable reaches the application process.

Do not assume a `.env` file is automatically read by a standalone Spring Boot JVM.

Do not assume Docker Compose interpolation automatically passes every variable to the application container.

If `.env` is missing, create it only when explicitly required and safe; never invent secrets or overwrite private values.

Never commit private `.env` files.

### 19.2 Externalization Rules

Externalize deployment-dependent settings, including where applicable:

- Database URL.
- Database username and password.
- JWT and JWKS configuration.
- Signing-key paths.
- External service endpoints.
- Environment-dependent ports.
- Connection and read timeouts.
- CORS origins.
- Logging destinations.
- Telemetry exporters.
- Resource limits.
- Feature flags.
- Other operationally configurable values.

Keep stable application invariants in code or application configuration when appropriate.

Do not turn every literal into an environment variable.

### 19.3 Default Values

Evaluate defaults individually.

Classify configuration as:

- Required without default.
- Safe production default.
- Development-only default.
- Optional configuration.
- Stable application constant.

Security-sensitive settings must fail safely.

Production must not silently fall back to insecure development credentials, endpoints, or permissions.

Use Spring property placeholders where appropriate.

Examples:

`${DATABASE_URL}`

`${REQUEST_TIMEOUT:5s}`

These are illustrative patterns, not instructions to introduce these exact variables.

Follow existing project naming conventions.

### 19.4 Hardcoding

Never hardcode:

- Passwords.
- API keys.
- JWT secrets.
- Private keys.
- Real patient UUIDs.
- Real staff UUIDs.
- Environment-specific service URLs.
- Machine-specific file paths.
- Temporary authorization mappings.

Hardcoding may be appropriate for genuine domain invariants, protocol constants, enum values, and other stable values.

Avoid unnecessary configuration complexity.

## 20. Production-Oriented Engineering

All changes must be designed with production behavior in mind.

Consider:

- Secure defaults.
- Input validation.
- Least-privilege authorization.
- Sensitive-data minimization.
- Transaction integrity.
- Concurrency.
- Idempotency where applicable.
- Database performance.
- Resource usage.
- Timeouts.
- Failure handling.
- Auditability.
- Observability.
- Backward compatibility.
- Operational configuration.
- Deployment safety.
- Testability.

Do not introduce speculative infrastructure.

Do not claim production readiness based only on successful compilation or unit tests.

Document unresolved risks.

## 21. Performance and Resource Management

Avoid unnecessary database queries.

Prevent N+1 loading.

Prefer projections when only a subset of entity fields is needed.

Use bounded pagination.

Avoid loading entire large tables into memory.

Avoid unbounded collections and excessive intermediate allocations.

Use appropriate database indexes based on verified query patterns.

Avoid premature optimization.

Measure performance when making performance-sensitive changes.

Do not cache sensitive clinical information without an approved security and invalidation policy.

## 22. Testing Requirements

Use the existing JUnit Jupiter, Mockito, AssertJ, MockMvc, and Spring Boot testing conventions.

Tests must be readable and behavior-oriented.

Use descriptive test method names.

Cover applicable cases:

- Successful behavior.
- Validation failures.
- Missing resources.
- Unauthorized access.
- Forbidden access.
- Business conflicts.
- Transaction rollback.
- Audit persistence.
- Response field restrictions.
- Concurrency.
- Database constraints.
- Configuration behavior.

Do not write tests that merely reproduce implementation details.

Prefer meaningful assertions.

Avoid unnecessary mocking.

Use integration tests when correctness depends on real PostgreSQL behavior.

### 22.1 Existing Database Test Gates

The audited repository includes opt-in PostgreSQL test gates:

- `CLINICAL_DATABASE_TESTS`
- `CLINICAL_REGISTRATION_TESTS`
- `CLINICAL_SEARCH_TESTS`
- `CLINICAL_ENCOUNTER_TESTS`
- `HOSPITAL_DATABASE_TESTS`

These tests require approved isolated database environments.

Never point destructive or fixture-writing tests at a shared or production database.

Do not claim database verification when these tests were skipped.

### 22.2 Maven Verification

Use the Maven Wrapper.

Windows:

`.\mvnw.cmd -B -ntp test`

POSIX:

`./mvnw -B -ntp test`

Use offline mode only when appropriate for the environment.

Report:

- Passed tests.
- Failed tests.
- Skipped tests.
- Tests not executed.
- Environment limitations.

Never alter tests merely to hide failures.

## 23. Docker and Deployment Safety

Respect the parent project's Docker Compose ownership.

Do not modify unrelated service definitions.

Do not execute destructive Docker commands without explicit authorization.

Do not delete:

- PostgreSQL volumes.
- Clinical data.
- Persistent logs.
- Database schemas.
- Existing containers or infrastructure state unnecessarily.

Do not run deployment or migration commands against shared environments without authorization.

Use safe configuration validation when possible.

Keep private keys and credentials outside Git.

Preserve existing health checks and observability integration.

## 24. Documentation

Update documentation when behavior or contracts change.

Relevant documentation may include:

- README.
- API-specific guides.
- Database documentation.
- Architecture documentation.
- OpenAPI annotations.
- Configuration examples.
- Development roadmap.

Documentation must reflect implemented behavior.

Clearly distinguish:

- Implemented functionality.
- Planned functionality.
- Deprecated functionality.
- Unverified assumptions.

Do not describe planned features as implemented.

Do not rewrite unrelated documentation unnecessarily.

## 25. Dependency and Architecture Changes

Before adding a dependency:

1. Check whether an existing dependency provides the capability.
2. Determine whether the dependency is genuinely necessary.
3. Evaluate compatibility with the current Spring Boot and Java versions.
4. Consider maintenance and security implications.
5. Avoid redundant libraries.

Do not add frameworks merely for convenience.

Do not change the project architecture without explicit authorization.

Do not create additional services or modules without a clear approved requirement.

## 26. Git and Change Management

Before making changes, inspect the working tree.

Preserve existing user modifications.

Do not overwrite unrelated work.

Do not change branches without authorization.

Do not stage, commit, push, reset, clean, or rebase unless explicitly requested.

Do not modify Git submodule references without authorization.

Keep changes limited to the requested task.

Avoid unrelated formatting or refactoring.

When modifying existing code, preserve its public behavior unless the task requires otherwise.

## 27. Mandatory Code Review Checklist

Before completing any implementation, verify:

- [ ] `docs/java-code-style.md` was read.
- [ ] Code follows applicable R01–R19 rules.
- [ ] SOLID and Clean Code principles are respected.
- [ ] Names are descriptive and consistent.
- [ ] No meaningful duplicated logic was introduced.
- [ ] Methods have clear responsibilities.
- [ ] Existing reusable components were considered.
- [ ] Package organization is appropriate.
- [ ] No unnecessary abstractions were added.
- [ ] Business validation is correct.
- [ ] Authorization is explicitly reviewed.
- [ ] Sensitive patient information is protected.
- [ ] Audit requirements are satisfied.
- [ ] Transaction boundaries are correct.
- [ ] Database mappings match the verified schema.
- [ ] No unsafe migration changes were introduced.
- [ ] Logging is structured and privacy-safe.
- [ ] Configuration and environment examples are synchronized.
- [ ] Default values are appropriate.
- [ ] No inappropriate hardcoded values were introduced.
- [ ] Relevant tests were added or updated.
- [ ] Relevant verification was executed.
- [ ] Existing contracts remain compatible.
- [ ] No unrelated files were changed.
- [ ] Documentation is updated where necessary.

If an item is not applicable, do not invent work merely to satisfy the checklist.

## 28. Definition of Done

A task is complete only when:

1. The requested behavior is implemented correctly.
2. The implementation follows the repository architecture.
3. Java code complies with `docs/java-code-style.md`.
4. SOLID, Clean Code, naming, and reusability standards are satisfied.
5. Security, privacy, and audit implications have been reviewed.
6. Database and transaction behavior is correct.
7. Configuration changes are synchronized.
8. Relevant tests have been executed.
9. The final diff contains no accidental or unrelated changes.
10. Remaining limitations and unverified requirements are disclosed.

Do not report unexecuted verification as successful.

Do not claim a task is production-ready while known blocking risks remain.

## 29. Final Response Requirements

After implementing a task, provide:

1. Summary of changes.
2. Important design decisions.
3. Files created, modified, or removed.
4. Reused components.
5. Security and database implications.
6. Configuration changes, including `.env.example` updates.
7. Tests executed and results.
8. Tests skipped or not executed.
9. Remaining risks or limitations.

Keep the final report concise but technically accurate.

## 30. Final Engineering Principle

Always prefer code that is:

**Correct, secure, readable, maintainable, reusable, testable, and appropriately simple.**

Do not optimize for writing fewer lines.

Do not introduce complexity merely to demonstrate architectural patterns.

Do not sacrifice patient safety, data integrity, security, or readability for implementation speed.

**The goal is production-oriented engineering that remains understandable and maintainable as the Hospital application grows.**