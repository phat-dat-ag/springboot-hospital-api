package com.hospitalmanagement.hospital.patient;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSearchResponse;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSummaryResponse;
import com.hospitalmanagement.hospital.patient.service.PatientSearchService;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL tests; uses only a dedicated disposable search database.
 */
@EnabledIfEnvironmentVariable(named = "CLINICAL_SEARCH_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.flyway.enabled=true",
        "spring.flyway.default-schema=public",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientSearchIntegrationTest {
    private static final UUID DEMOGRAPHICS_STAFF_ID = UUID.randomUUID();
    private static final UUID DEMOGRAPHICS_PATIENT_ID = UUID.randomUUID();

    private static final UUID FAILURE_ACTOR =
            UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final Instant SAME_CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    private PatientSearchService searches;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url = Objects.requireNonNull(System.getenv("CLINICAL_SEARCH_JDBC_URL"));

        if (!url.matches("jdbc:postgresql://[^/]+/clinical_search_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Use the dedicated clinical_search_test database");
        }

        properties.add("spring.datasource.url", () -> url);
        properties.add(
                "spring.datasource.username",
                () -> Objects.requireNonNull(
                        System.getenv("CLINICAL_SEARCH_DB_USER")
                )
        );
        properties.add(
                "spring.datasource.password",
                () -> Objects.requireNonNull(
                        System.getenv("CLINICAL_SEARCH_DB_PASSWORD")
                )
        );
    }

    @BeforeAll
    void installTestOnlyAccessLogFailure() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_search_log_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.actor_user_id = '00000000-0000-0000-0000-00000000f001'::uuid
                        AND EXISTS (SELECT 1 FROM medical_record_access_logs
                            WHERE actor_user_id = NEW.actor_user_id) THEN
                        RAISE EXCEPTION 'Injected access-log failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_search_log_failure
                BEFORE INSERT ON medical_record_access_logs
                FOR EACH ROW EXECUTE FUNCTION test_search_log_failure()
                """);
    }

    @Test
    void readsActiveDemographicsAndCommitsAccessLogInPostgres() throws Exception {
        jdbc.update(
                """
                        INSERT INTO patients (id, medical_record_number, given_name, sex_at_birth)
                        VALUES (?, ?, ?, ?)
                        """,
                DEMOGRAPHICS_PATIENT_ID,
                "pat-" + DEMOGRAPHICS_PATIENT_ID,
                "Example",
                "unknown"
        );

        String path = "/api/v1/hospital/patients/" + DEMOGRAPHICS_PATIENT_ID;

        mvc.perform(get(path).with(jwt()
                        .jwt(token -> token.subject(DEMOGRAPHICS_STAFF_ID.toString()))
                        .authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(DEMOGRAPHICS_PATIENT_ID.toString()))
                .andExpect(jsonPath("$.data.sexAtBirth").value("unknown"))
                .andExpect(jsonPath("$.data.updatedAt").exists());

        Integer committedEvents = jdbc.queryForObject(
                """
                        SELECT count(*) FROM medical_record_access_logs
                        WHERE patient_id = ? AND actor_user_id = ? AND operation = 'view'
                          AND outcome = 'allowed' AND correlation_id IS NOT NULL
                        """,
                Integer.class,
                DEMOGRAPHICS_PATIENT_ID,
                DEMOGRAPHICS_STAFF_ID
        );

        assertThat(committedEvents).isEqualTo(1);
    }

    @Test
    void filtersByExactMrnNameAndBirthDateWithCorrectAndOrGrouping() {
        Fixture fixture = fixture();

        assertThat(search(fixture.firstMrn(), null, null).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.first());
        assertThat(search(fixture.firstMrn().toUpperCase(), null, null).items()).isEmpty();
        assertThat(search(null, fixture.prefix().toUpperCase(), null).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactlyInAnyOrder(fixture.first(), fixture.second());
        assertThat(search(null, null, fixture.birthDate()).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.first());
        assertThat(search(null, fixture.prefix(), fixture.birthDate()).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.first());
        assertThat(search(fixture.firstMrn(), fixture.prefix(), fixture.birthDate()).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.first());
        assertThat(search(fixture.firstMrn(), "No matching name", null).items()).isEmpty();
        assertThat(search(fixture.deletedMrn(), null, null).items()).isEmpty();
    }

    @Test
    void usesStableUuidTiesCorrectCountsAndNoLogsForEmptyPages() {
        Fixture fixture = fixture();

        UUID actor = UUID.randomUUID();

        PatientSearchRequest firstPage = criteria(fixture.prefix(), 0, 1, "createdAt,desc");
        PatientSearchResponse first = searches.searchPatients(firstPage, actor.toString());
        PatientSearchResponse repeated = searches.searchPatients(firstPage, actor.toString());
        PatientSearchResponse second = searches.searchPatients(
                criteria(fixture.prefix(), 1, 1, "createdAt,desc"), actor.toString()
        );

        List<UUID> sorted = List.of(fixture.first(), fixture.second())
                .stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        assertThat(first.items()).isEqualTo(repeated.items());
        assertThat(first.items().get(0).id()).isEqualTo(sorted.get(0));
        assertThat(second.items().get(0).id()).isEqualTo(sorted.get(1));
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.size()).isEqualTo(1);
        assertThat(accessCount(actor)).isEqualTo(3);

        PatientSearchResponse beyond = searches.searchPatients(
                criteria(fixture.prefix(), 2, 1, "createdAt,desc"), actor.toString()
        );

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(2);
        assertThat(accessCount(actor)).isEqualTo(3);

        PatientSearchResponse missing = searches.searchPatients(
                criteria("NoMatches-" + UUID.randomUUID(), 0, 20, "createdAt,desc"),
                actor.toString()
        );

        assertThat(missing.items()).isEmpty();
        assertThat(missing.totalElements()).isZero();
        assertThat(missing.totalPages()).isZero();
        assertThat(accessCount(actor)).isEqualTo(3);
    }

    @Test
    void sortsByMrnInBothDirections() {
        Fixture fixture = fixture();

        var ascending = searches.searchPatients(
                criteria(fixture.prefix(), 0, 20, "medicalRecordNumber,asc"),
                UUID.randomUUID().toString()
        );

        var descending = searches.searchPatients(
                criteria(fixture.prefix(), 0, 20, "medicalRecordNumber,desc"),
                UUID.randomUUID().toString()
        );

        assertThat(ascending.items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.first(), fixture.second());
        assertThat(descending.items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(fixture.second(), fixture.first());
    }

    @Test
    void treatsLikeWildcardsEscapeCharactersAndSqlSyntaxAsLiteralInput() {
        String prefix = "literal-" + UUID.randomUUID().toString().substring(0, 8);

        LocalDate date = unusedBirthDate();

        UUID expected = insertPatient(prefix + "-a", prefix + " %_! exact", null, date, false);

        insertPatient(prefix + "-b", prefix + " %x! decoy", null, date, false);
        insertPatient(prefix + "-c", prefix + " xx! decoy", null, date, false);

        assertThat(search(null, prefix + " %_!", null).items())
                .extracting(PatientSummaryResponse::id)
                .containsExactly(expected);
        assertThat(search(null, "' OR 1=1 --", null).items()).isEmpty();
        assertThat(search("' OR 1=1 --", null, null).items()).isEmpty();
    }

    @Test
    void recordsOneMetadataOnlyEventPerReturnedPatient() {
        Fixture fixture = fixture();

        UUID actor = UUID.randomUUID();

        PatientSearchResponse result = searches.searchPatients(
                criteria(fixture.prefix(), 0, 20, "createdAt,desc"),
                actor.toString()
        );

        var events = jdbc.queryForList(
                "SELECT * FROM medical_record_access_logs WHERE actor_user_id = ?", actor
        );

        assertThat(result.items()).hasSize(2);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.get("patient_id"))
                .containsExactlyInAnyOrder(fixture.first(), fixture.second());

        for (var event : events) {
            assertThat(event)
                    .containsEntry("actor_user_id", actor)
                    .containsEntry("actor_type", "user")
                    .containsEntry("operation", "search")
                    .containsEntry("purpose_code", "patient_lookup")
                    .containsEntry("entity_type", "patients")
                    .containsEntry("entity_id", event.get("patient_id"))
                    .containsEntry("outcome", "allowed");
            assertThat(event.get("occurred_at")).isNotNull();
            assertThat(event.toString()).doesNotContain(fixture.prefix());
        }
    }

    @Test
    void accessLogFailureReturnsNoDataAndRollsBackEarlierLogInserts() throws Exception {
        Fixture fixture = fixture();

        assertThat(accessCount(FAILURE_ACTOR)).isZero();

        mvc.perform(get("/api/v1/hospital/patients")
                        .with(jwt().jwt(token -> token.subject(FAILURE_ACTOR.toString()))
                                .authorities(createAuthorityList("ROLE_STAFF")))
                        .param("name", fixture.prefix()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESS_LOG_FAILED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(accessCount(FAILURE_ACTOR)).isZero();
    }

    @Test
    void projectionAndPatientReferencesDoNotTriggerNPlusOneReads() {
        Fixture fixture = fixture();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();

        PatientSearchResponse response = searches.searchPatients(
                criteria(fixture.prefix(), 0, 1, "createdAt,desc"), UUID.randomUUID().toString()
        );

        assertThat(response.items()).hasSize(1);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityFetchCount()).isZero();
        assertThat(statistics.getEntityLoadCount()).isZero();
        // One page SELECT, one count SELECT, one access-log INSERT.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void rejectsMissingFiltersExcessiveOffsetsAndInvalidDirectServiceCriteria() {
        String actor = UUID.randomUUID().toString();
        assertThatThrownBy(() -> searches.searchPatients(
                new PatientSearchRequest(null, null, null, null, null, null), actor
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PATIENT_SEARCH_FILTER_REQUIRED));
        assertThatThrownBy(() -> searches.searchPatients(
                criteria("Example", Integer.MAX_VALUE, 100, "createdAt,desc"), actor
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PATIENT_SEARCH_PAGE_OUT_OF_RANGE));
        assertThatThrownBy(() -> searches.searchPatients(
                criteria("Example", 0, 101, "createdAt,desc"), actor
        )).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> searches.searchPatients(
                criteria("Example", 0, 20, "createdAt,desc"), "not-a-uuid"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACTOR_ID));
    }

    private PatientSearchResponse search(String mrn, String name, LocalDate date) {
        return searches.searchPatients(
                new PatientSearchRequest(mrn, name, date, null, null, null),
                UUID.randomUUID().toString()
        );
    }

    private PatientSearchRequest criteria(String name, int page, int size, String sort) {
        return new PatientSearchRequest(null, name, null, page, size, sort);
    }

    private long accessCount(UUID actor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM medical_record_access_logs WHERE actor_user_id = ?",
                Long.class,
                actor
        );
    }

    private Fixture fixture() {
        String prefix = "search-" + UUID.randomUUID().toString().substring(0, 8);

        LocalDate birthDate = unusedBirthDate();

        UUID first = insertPatient(prefix + "-a", prefix + " Alice", "Family", birthDate, false);
        UUID second = insertPatient(
                prefix + "-b", "Other", prefix + " Bob", birthDate.plusDays(1), false
        );

        insertPatient(prefix + "-deleted", prefix + " Hidden", null, birthDate, true);

        return new Fixture(prefix, birthDate, first, second, prefix + "-a", prefix + "-deleted");
    }

    private LocalDate unusedBirthDate() {
        LocalDate candidate;

        do {
            candidate = LocalDate.of(1900, 1, 1)
                    .plusDays(ThreadLocalRandom.current().nextInt(40_000));
        } while (jdbc.queryForObject(
                "SELECT count(*) FROM patients WHERE date_of_birth BETWEEN ? AND ?",
                Integer.class,
                candidate,
                candidate.plusDays(1)
        ) != 0);

        return candidate;
    }

    private UUID insertPatient(
            String mrn,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            boolean deleted
    ) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                        INSERT INTO patients(id, medical_record_number, given_name, family_name,
                            date_of_birth, created_at, updated_at, deleted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                mrn,
                givenName,
                familyName,
                dateOfBirth,
                Timestamp.from(SAME_CREATED_AT),
                Timestamp.from(SAME_CREATED_AT),
                deleted ? Timestamp.from(SAME_CREATED_AT) : null
        );

        return id;
    }

    private record Fixture(
            String prefix,
            LocalDate birthDate,
            UUID first,
            UUID second,
            String firstMrn,
            String deletedMrn
    ) {
    }
}
