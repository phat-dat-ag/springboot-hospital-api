package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterStatusResponse;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.service.EncounterStatusService;
import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.service.PatientService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Committed fixtures are restricted to a disposable database; never deletes clinical rows.
 */
@EnabledIfEnvironmentVariable(named = "CLINICAL_ENCOUNTER_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "spring.flyway.default-schema=public",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.sql.init.mode=never"
})
@Import(EncounterStatusIntegrationTest.FixedClinicClock.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncounterStatusIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-18T18:00:00Z");
    private static final UUID HISTORY_FAILURE =
            UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID AUDIT_FAILURE =
            UUID.fromString("00000000-0000-0000-0000-00000000e002");

    @Autowired
    private EncounterStatusService encounters;

    @Autowired
    private PatientService registrations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url = Objects.requireNonNull(System.getenv("CLINICAL_ENCOUNTER_JDBC_URL"));

        if (!url.matches("jdbc:postgresql://[^/]+/clinical_encounter_test(?:\\?.*)?")) {
            throw new IllegalArgumentException(
                    "Use the dedicated clinical_encounter_test database"
            );
        }

        properties.add("spring.datasource.url", () -> url);
        properties.add(
                "spring.datasource.username",
                () -> Objects.requireNonNull(
                        System.getenv("CLINICAL_ENCOUNTER_DB_USER")
                )
        );
        properties.add(
                "spring.datasource.password",
                () -> Objects.requireNonNull(
                        System.getenv("CLINICAL_ENCOUNTER_DB_PASSWORD")
                )
        );
    }

    @BeforeAll
    void installTestOnlyFailureTriggers() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_encounter_event_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF (TG_TABLE_NAME = 'encounter_status_histories'
                        AND NEW.actor_user_id = '00000000-0000-0000-0000-00000000e001'::uuid)
                        OR (TG_TABLE_NAME = 'clinical_audit_logs'
                        AND NEW.actor_user_id = '00000000-0000-0000-0000-00000000e002'::uuid)
                    THEN
                        RAISE EXCEPTION 'Injected encounter event failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);

        for (String table : List.of("encounter_status_histories", "clinical_audit_logs")) {
            jdbc.execute(
                    "CREATE OR REPLACE TRIGGER test_encounter_event_failure BEFORE INSERT ON "
                            + table
                            + " FOR EACH ROW EXECUTE FUNCTION test_encounter_event_failure()"
            );
        }

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_encounter_update_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.encounter_number LIKE 'ERR-%' AND NEW.status <> OLD.status THEN
                        RAISE EXCEPTION 'Injected encounter update failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);

        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_encounter_update_failure
                BEFORE UPDATE ON medical_encounters
                FOR EACH ROW EXECUTE FUNCTION test_encounter_update_failure()
                """);
    }

    @Test
    void progressesThroughVisitAndReturnsDatabaseUpdatedTimestampWithAtomicEvents() {
        Fixture fixture = fixture("scheduled", true);

        Timestamp created = timestamp(fixture.encounter(), "created_at");
        Timestamp originalUpdated = timestamp(fixture.encounter(), "updated_at");

        EncounterStatusResponse arrived = change(
                fixture,
                "scheduled",
                "arrived",
                null
        );

        assertThat(arrived.id()).isEqualTo(fixture.encounter());
        assertThat(arrived.patientId()).isEqualTo(fixture.patient());
        assertThat(arrived.startedAt()).isNull();
        assertThat(arrived.endedAt()).isNull();
        assertThat(arrived.updatedAt()).isAfter(originalUpdated.toInstant());
        assertThat(arrived.updatedAt()).isEqualTo(
                timestamp(fixture.encounter(), "updated_at").toInstant()
        );

        EncounterStatusResponse started = change(
                fixture,
                "arrived",
                "in_progress",
                null
        );

        assertThat(started.startedAt()).isEqualTo(NOW);

        EncounterStatusResponse completed = change(
                fixture,
                "in_progress",
                "completed",
                null
        );

        assertThat(completed.startedAt()).isEqualTo(NOW);
        assertThat(completed.endedAt()).isEqualTo(NOW);
        assertThat(timestamp(fixture.encounter(), "created_at")).isEqualTo(created);
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isEqualTo(3);
        assertThat(count(
                "clinical_audit_logs",
                "entity_id",
                fixture.encounter()
        )).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT from_status || ':' || to_status FROM encounter_status_histories
                WHERE encounter_id = ?
                """, String.class, fixture.encounter()))
                .containsExactlyInAnyOrder(
                        "scheduled:arrived",
                        "arrived:in_progress",
                        "in_progress:completed"
                );
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM clinical_audit_logs
                        WHERE entity_id = ? AND patient_id = ? AND actor_user_id = ?
                            AND actor_type = 'user' AND operation = 'update'
                            AND entity_type = 'medical_encounters' AND occurred_at = ?
                        """,
                Long.class,
                fixture.encounter(),
                fixture.patient(),
                fixture.actor(),
                Timestamp.from(NOW)
        )).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM encounter_status_histories
                        WHERE encounter_id = ? AND actor_user_id = ? AND occurred_at = ?
                        """,
                Long.class,
                fixture.encounter(),
                fixture.actor(),
                Timestamp.from(NOW)
        )).isEqualTo(3);
    }

    @Test
    void cancelsScheduledAndArrivedVisitsAndRejectsBlankReason() {
        for (String state : List.of("scheduled", "arrived")) {
            Fixture fixture = fixture(state, false);

            assertError(ErrorCode.ENCOUNTER_REASON_REQUIRED, () ->
                    change(fixture, state, "cancelled", "   ")
            );
            assertNoEvents(fixture);

            change(fixture, state, "cancelled", "  Patient requested cancellation  ");

            assertThat(jdbc.queryForObject(
                    "SELECT cancellation_reason FROM medical_encounters WHERE id = ?",
                    String.class, fixture.encounter()
            )).isEqualTo("Patient requested cancellation");
            assertThat(jdbc.queryForObject(
                    "SELECT reason FROM encounter_status_histories WHERE encounter_id = ?",
                    String.class, fixture.encounter()
            )).isEqualTo("Patient requested cancellation");
            assertThat(jdbc.queryForObject(
                    "SELECT operation FROM clinical_audit_logs WHERE entity_id = ?",
                    String.class, fixture.encounter()
            )).isEqualTo("cancel");
        }
    }

    @Test
    void rejectsStaleRepeatedAndTerminalTransitionsWithoutAdditionalEvents() {
        Fixture fixture = fixture("scheduled", true);

        change(fixture, "scheduled", "arrived", null);

        assertError(
                ErrorCode.ENCOUNTER_STATUS_CONFLICT,
                () -> change(fixture, "scheduled", "arrived", null)
        );
        assertError(
                ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED,
                () -> change(fixture, "arrived", "arrived", null)
        );
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isEqualTo(1);

        for (String state : List.of("completed", "cancelled", "entered_in_error")) {
            Fixture terminal = fixture(state, true);

            assertError(
                    ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED,
                    () -> change(terminal, state, "arrived", null)
            );
            assertNoEvents(terminal);
        }
    }

    @Test
    void rejectsUnknownEncounterAndInactivePatient() {
        Fixture fixture = fixture("arrived", true);

        assertError(ErrorCode.ENCOUNTER_NOT_FOUND, () -> encounters.changeStatus(
                UUID.randomUUID(),
                request("arrived", "in_progress", null),
                fixture.actor().toString()
        ));

        jdbc.update(
                "UPDATE patients SET deleted_at = clock_timestamp() WHERE id = ?",
                fixture.patient()
        );

        assertError(
                ErrorCode.ENCOUNTER_PATIENT_INACTIVE,
                () -> change(fixture, "arrived", "in_progress", null)
        );
        assertNoEvents(fixture);
    }

    @Test
    void checksDoctorAssignmentDeletionAndClinicDateExpiry() {
        Fixture missing = fixture("arrived", false);

        assertError(
                ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE,
                () -> change(missing, "arrived", "in_progress", null)
        );

        assertNoEvents(missing);

        Fixture deleted = fixture("arrived", true);

        jdbc.update(
                "UPDATE doctors SET deleted_at = clock_timestamp() WHERE id = ?",
                deleted.doctor()
        );

        assertError(
                ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE,
                () -> change(deleted, "arrived", "in_progress", null)
        );

        assertNoEvents(deleted);

        Fixture expired = fixture("arrived", true);

        jdbc.update(
                "UPDATE doctors SET license_expires_on = DATE '2026-11-18' WHERE id = ?",
                expired.doctor()
        );

        assertError(
                ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE,
                () -> change(expired, "arrived", "in_progress", null)
        );

        assertNoEvents(expired);

        Fixture today = fixture("arrived", true);

        jdbc.update(
                "UPDATE doctors SET license_expires_on = DATE '2026-11-19' WHERE id = ?",
                today.doctor()
        );

        assertThat(change(
                today,
                "arrived",
                "in_progress",
                null
        ).startedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsClockBeforeStartWithoutUpdatingEncounter() {
        Fixture fixture = fixture("in_progress", true);

        jdbc.update(
                "UPDATE medical_encounters SET started_at = ? WHERE id = ?",
                Timestamp.from(NOW.plusSeconds(60)), fixture.encounter()
        );

        assertError(
                ErrorCode.ENCOUNTER_START_REQUIRED,
                () -> change(fixture, "in_progress", "completed", null)
        );
        assertNoEvents(fixture);
        assertThat(timestamp(fixture.encounter(), "ended_at")).isNull();
    }

    @Test
    void rollsBackStatusTimestampsHistoryAndAuditWhenEitherEventInsertFails() {
        for (UUID actor : List.of(HISTORY_FAILURE, AUDIT_FAILURE)) {
            Fixture fixture = fixture("arrived", true);

            Timestamp originalUpdated = timestamp(fixture.encounter(), "updated_at");

            assertError(
                    ErrorCode.ENCOUNTER_CHANGE_FAILED,
                    () -> encounters.changeStatus(
                            fixture.encounter(),
                            request("arrived", "in_progress", null),
                            actor.toString()
                    )
            );

            assertThat(currentStatus(fixture)).isEqualTo("arrived");
            assertThat(timestamp(fixture.encounter(), "started_at")).isNull();
            assertThat(timestamp(fixture.encounter(), "updated_at")).isEqualTo(originalUpdated);
            assertNoEvents(fixture);
        }
    }

    @Test
    void simultaneousIndependentTransactionsProduceOneSuccessAndOneStaleConflict()
            throws Exception {
        Fixture fixture = fixture("arrived", true);

        var executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            var first = executor.submit(() -> race(fixture, ready, start));
            var second = executor.submit(() -> race(fixture, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            assertThat(
                    List.of(
                            first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS)
                    )
            ).containsExactlyInAnyOrder("success", "ENCOUNTER_STATUS_CONFLICT");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(currentStatus(fixture)).isEqualTo("in_progress");
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isEqualTo(1);
        assertThat(count(
                "clinical_audit_logs",
                "entity_id",
                fixture.encounter()
        )).isEqualTo(1);
    }

    @Test
    void boundedLockWaitReturnsSafeConflictAndNoPartialWrites() throws Exception {
        Fixture fixture = fixture("arrived", true);

        var executor = Executors.newSingleThreadExecutor();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);

            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM medical_encounters WHERE id = ? FOR UPDATE"
            )) {
                statement.setObject(1, fixture.encounter());
                statement.executeQuery().close();
            }

            try {
                var attempt = executor.submit(() -> {
                    try {
                        change(fixture, "arrived", "in_progress", null);

                        return "unexpected success";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().name();
                    }
                });

                assertThat(attempt.get(8, TimeUnit.SECONDS))
                        .isEqualTo("ENCOUNTER_LOCK_CONFLICT");
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(currentStatus(fixture)).isEqualTo("arrived");
        assertNoEvents(fixture);
    }

    @Test
    void realHttpFlowValidatesCancellationAndCommitsOneAtomicChange() throws Exception {
        Fixture fixture = fixture("scheduled", false);

        String path = "/api/v1/hospital/encounters/" + fixture.encounter() + "/status";

        mvc.perform(patch(path)
                        .with(jwt()
                                .jwt(token -> token.subject(fixture.actor().toString()))
                                .authorities(createAuthorityList("ROLE_ADMIN"))
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"scheduled\",\"status\":\"cancelled\"}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENCOUNTER_REASON_REQUIRED"));

        assertNoEvents(fixture);

        mvc.perform(patch(path)
                        .with(jwt()
                                .jwt(token -> token.subject(fixture.actor().toString()))
                                .authorities(createAuthorityList("ROLE_ADMIN"))
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"scheduled\",\"status\":\"arrived\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("arrived"))
                .andExpect(jsonPath("$.data.updatedAt").exists());
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isEqualTo(1);
        assertThat(count(
                "clinical_audit_logs",
                "entity_id",
                fixture.encounter()
        )).isEqualTo(1);
    }

    @Test
    void encounterUpdateFailureRollsBackPreparedHistoryAndAudit() {
        Fixture fixture = fixture("arrived", true);

        jdbc.update(
                "UPDATE medical_encounters SET encounter_number = ? WHERE id = ?",
                "ERR-" + fixture.encounter(), fixture.encounter()
        );

        Timestamp original = timestamp(fixture.encounter(), "updated_at");

        assertError(
                ErrorCode.ENCOUNTER_CHANGE_FAILED,
                () -> change(fixture, "arrived", "in_progress", null)
        );

        assertThat(currentStatus(fixture)).isEqualTo("arrived");
        assertThat(timestamp(fixture.encounter(), "updated_at")).isEqualTo(original);
        assertThat(timestamp(fixture.encounter(), "started_at")).isNull();
        assertNoEvents(fixture);
    }

    @Test
    void concurrentSoftDeletionIsObservedAfterPatientAndDoctorLocks() throws Exception {
        for (String table : List.of("patients", "doctors")) {
            Fixture fixture = fixture("arrived", true);

            UUID id = table.equals("patients") ? fixture.patient() : fixture.doctor();

            var executor = Executors.newSingleThreadExecutor();

            try (Connection blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);

                try (var statement = blocker.prepareStatement(
                        "UPDATE " + table + " SET deleted_at = clock_timestamp() WHERE id = ?"
                )) {
                    statement.setObject(1, id);
                    statement.executeUpdate();
                }

                try {
                    var attempt = executor.submit(() -> {
                        try {
                            change(fixture, "arrived", "in_progress", null);

                            return "unexpected success";
                        } catch (BusinessException exception) {
                            return exception.getErrorCode().name();
                        }
                    });

                    awaitBlockedLookup(table);

                    blocker.commit();

                    assertThat(attempt.get(5, TimeUnit.SECONDS)).isEqualTo(
                            table.equals("patients")
                                    ? "ENCOUNTER_PATIENT_INACTIVE"
                                    : "ENCOUNTER_DOCTOR_UNAVAILABLE"
                    );
                } finally {
                    blocker.rollback();
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(currentStatus(fixture)).isEqualTo("arrived");
            assertNoEvents(fixture);
        }
    }

    private void awaitBlockedLookup(String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                        AND query LIKE ?
                    """, Long.class, "%" + table + "%");

            if (waiting != null && waiting > 0) {
                return;
            }

            Thread.sleep(20);
        }

        throw new AssertionError("Expected blocked " + table + " lookup");
    }

    @Test
    void registrationStillUsesDatabaseDefaultAuditTimestamp() {
        var registered = registrations.registerPatient(new RegisterPatientRequest(
                "REG-" + UUID.randomUUID(), "Synthetic", null, null, null, List.of()
        ), UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject(
                "SELECT occurred_at FROM clinical_audit_logs WHERE patient_id = ?",
                Timestamp.class, registered.id()
        )).isNotNull();
    }

    private String race(
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }

        try {
            change(fixture, "arrived", "in_progress", null);

            return "success";
        } catch (BusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private Fixture fixture(String status, boolean assignedDoctor) {
        UUID patient = UUID.randomUUID();
        UUID doctor = assignedDoctor ? UUID.randomUUID() : null;
        UUID encounter = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO patients (id, medical_record_number, given_name) VALUES (?, ?, ?)",
                patient, "P-" + patient, "Synthetic encounter patient"
        );

        if (doctor != null) {
            jdbc.update("""
                    INSERT INTO doctors (id, license_authority, license_number, given_name)
                    VALUES (?, 'TEST_ONLY', ?, 'Synthetic doctor')
                    """, doctor, doctor.toString());
        }

        Instant started = List.of("in_progress", "completed").contains(status) ? NOW : null;
        Instant ended = status.equals("completed") ? NOW : null;

        jdbc.update("""
                        INSERT INTO medical_encounters
                            (id, encounter_number, patient_id, doctor_id, status,
                            started_at, ended_at, cancellation_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, encounter, "E-" + encounter, patient, doctor, status,
                started == null ? null : Timestamp.from(started),
                ended == null ? null : Timestamp.from(ended),
                status.equals("cancelled") ? "Test fixture" : null);

        return new Fixture(encounter, patient, doctor, UUID.randomUUID());
    }

    private EncounterStatusResponse change(
            Fixture fixture,
            String expected,
            String target,
            String reason
    ) {
        return encounters.changeStatus(
                fixture.encounter(),
                request(expected, target, reason),
                fixture.actor().toString()
        );
    }

    private ChangeEncounterStatusRequest request(
            String expected,
            String target,
            String reason
    ) {
        return new ChangeEncounterStatusRequest(
                EncounterStatus.valueOf(expected.toUpperCase(Locale.ROOT)),
                EncounterStatus.valueOf(target.toUpperCase(Locale.ROOT)),
                reason
        );
    }

    private Timestamp timestamp(UUID encounter, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM medical_encounters WHERE id = ?",
                Timestamp.class, encounter
        );
    }

    private String currentStatus(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM medical_encounters WHERE id = ?",
                String.class, fixture.encounter()
        );
    }

    private long count(String table, String column, UUID id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id
        ));
    }

    private void assertNoEvents(Fixture fixture) {
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isZero();
        assertThat(count("clinical_audit_logs", "entity_id", fixture.encounter())).isZero();
    }

    private void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo(code)
                );
    }

    record Fixture(UUID encounter, UUID patient, UUID doctor, UUID actor) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClinicClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Bangkok"));
        }
    }
}
