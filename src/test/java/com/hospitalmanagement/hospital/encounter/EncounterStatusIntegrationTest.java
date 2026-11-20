package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.client.UserServiceClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

import static com.hospitalmanagement.hospital.encounter.EncounterTestAuthentication.authentication;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @MockitoBean
    private UserServiceClient accounts;

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
                        AND NEW.actor_user_id IN (
                            '00000000-0000-0000-0000-00000000e002'::uuid,
                            '00000000-0000-0000-0000-00000000e004'::uuid))
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

        assertThat(timestamp(fixture.encounter(), "ended_at")).isNull();
        assertThat(timestamp(fixture.encounter(), "created_at")).isEqualTo(created);
        assertThat(count(
                "encounter_status_histories",
                "encounter_id",
                fixture.encounter()
        )).isEqualTo(2);
        assertThat(count(
                "clinical_audit_logs",
                "entity_id",
                fixture.encounter()
        )).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT from_status || ':' || to_status FROM encounter_status_histories
                WHERE encounter_id = ?
                """, String.class, fixture.encounter()))
                .containsExactlyInAnyOrder(
                        "scheduled:arrived",
                        "arrived:in_progress"
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
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM encounter_status_histories
                        WHERE encounter_id = ? AND actor_user_id = ? AND occurred_at = ?
                        """,
                Long.class,
                fixture.encounter(),
                fixture.actor(),
                Timestamp.from(NOW)
        )).isEqualTo(2);
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
                authentication(fixture.actor().toString(), "ROLE_DOCTOR")
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
                ErrorCode.ENCOUNTER_NOT_FOUND,
                () -> change(missing, "arrived", "in_progress", null)
        );

        assertNoEvents(missing);

        Fixture deleted = fixture("arrived", true);

        jdbc.update(
                "UPDATE doctors SET deleted_at = clock_timestamp() WHERE id = ?",
                deleted.doctor()
        );

        assertError(
                ErrorCode.ENCOUNTER_ACCESS_DENIED,
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
    void rejectsCompletionWithoutUpdatingEncounter() {
        Fixture fixture = fixture("in_progress", true);

        jdbc.update(
                "UPDATE medical_encounters SET started_at = ? WHERE id = ?",
                Timestamp.from(NOW.plusSeconds(60)), fixture.encounter()
        );

        assertError(
                ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED,
                () -> change(fixture, "in_progress", "completed", null)
        );
        assertNoEvents(fixture);
        assertThat(timestamp(fixture.encounter(), "ended_at")).isNull();
    }

    @Test
    void queuesUseClinicDatesAndAssignmentAndAuditOnlyDisclosedRows() throws Exception {
        Fixture first = fixture("scheduled", true);
        Fixture other = fixture("scheduled", true);
        Fixture cancelled = fixture("cancelled", true);
        Instant startOfClinicDay = Instant.parse("2026-11-18T17:00:00Z");

        schedule(first, startOfClinicDay);
        schedule(other, startOfClinicDay);
        schedule(cancelled, startOfClinicDay);
        Fixture yesterday = fixture("scheduled", true);
        schedule(yesterday, startOfClinicDay.minusSeconds(1));
        String root = "/api/v1/hospital/encounters";
        UUID staff = UUID.randomUUID();

        mvc.perform(get(root).param("size", "1")
                        .with(jwt().jwt(token -> token.subject(staff.toString()))
                                .authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.date").value("2026-11-19"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].chiefComplaint").doesNotExist());
        assertThat(count("medical_record_access_logs", "actor_user_id", staff)).isEqualTo(1);

        mvc.perform(get(root + "/assigned").param("doctorId", other.doctor().toString())
                        .with(jwt().jwt(token -> token.subject(first.actor().toString()))
                                .authorities(createAuthorityList("ROLE_DOCTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.encounter().toString()));

        mvc.perform(get(root + "/" + other.encounter())
                        .with(jwt().jwt(token -> token.subject(first.actor().toString()))
                                .authorities(createAuthorityList("ROLE_DOCTOR"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENCOUNTER_NOT_FOUND"));
        assertThat(count("medical_record_access_logs", "actor_user_id", first.actor()))
                .isEqualTo(1);

        mvc.perform(get(root).param("status", "cancelled")
                        .with(jwt().jwt(token -> token.subject(staff.toString()))
                                .authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(cancelled.encounter().toString()))
                .andExpect(jsonPath("$.data.items[0].cancellationReason").doesNotExist());
        mvc.perform(get(root + "/" + cancelled.encounter())
                        .with(jwt().jwt(token -> token.subject(staff.toString()))
                                .authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancellationReason").value("Test fixture"));
    }

    @Test
    void arrivalIsDerivedFromCommittedHistoryAndReadAuditFailureWithholdsDetails()
            throws Exception {
        Fixture fixture = fixture("scheduled", true);
        change(fixture, "scheduled", "arrived", null);
        String path = "/api/v1/hospital/encounters/" + fixture.encounter();
        UUID actor = UUID.fromString("00000000-0000-0000-0000-00000000e003");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_p03_read_audit_failure()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.actor_user_id = '00000000-0000-0000-0000-00000000e003' THEN
                        RAISE EXCEPTION USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_p03_read_audit_failure
                BEFORE INSERT ON medical_record_access_logs FOR EACH ROW
                EXECUTE FUNCTION test_p03_read_audit_failure()
                """);
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(actor.toString()))
                        .authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESS_LOG_FAILED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(count("medical_record_access_logs", "actor_user_id", actor)).isZero();

        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(fixture.actor().toString()))
                        .authorities(createAuthorityList("ROLE_DOCTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrivedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.data.startedAt").doesNotExist());
    }

    private void schedule(Fixture fixture, Instant scheduledAt) {
        jdbc.update("UPDATE medical_encounters SET scheduled_at = ? WHERE id = ?",
                Timestamp.from(scheduledAt), fixture.encounter());
    }

    @Test
    void assignedDoctorRecordsAndReadsAuditedVitalMeasurements() throws Exception {
        Fixture active = fixture("in_progress", true);
        Fixture other = fixture("in_progress", true);

        String path = clinicalPath(active) + "/vitals";
        String body = "{\"measurements\":{\"heartRateBpm\":72,\"oxygenSaturationPct\":98}}";

        mvc.perform(post(path).with(doctor(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                .andExpect(status().isNotFound());
        mvc.perform(post(path).with(doctor(active))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurements\":{}}")
                )
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(doctor(active))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurements\":{\"oxygenSaturationPct\":101}}")
                )
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(doctor(active))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.measurements.heartRateBpm").value(72))
                .andExpect(jsonPath("$.data.measuredAt").value(NOW.toString()));
        mvc.perform(get(path).with(doctor(active)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        assertThat(jdbc.queryForObject("""
                SELECT recorded_by_user_id FROM vital_signs WHERE encounter_id = ?
                """, UUID.class, active.encounter())).isEqualTo(active.actor());
        assertThat(count("clinical_audit_logs", "actor_user_id", active.actor())).isEqualTo(1);
        assertThat(count("medical_record_access_logs", "actor_user_id", active.actor()))
                .isEqualTo(1);
    }

    @Test
    void signsOnlyTheReviewedDraftAndKeepsTheSignedContentImmutable() throws Exception {
        Fixture fixture = fixture("in_progress", true);
        String base = clinicalPath(fixture) + "/notes";

        var initial = createDraft(fixture);

        String id = initial.path("id").asString();
        String firstRevision = initial.path("revision").asString();
        String note = base + "/" + id;

        mvc.perform(get(base).with(doctor(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist());
        mvc.perform(get(note).with(doctor(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(firstRevision));
        var edited = mvc.perform(patch(note).with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Revised synthetic text","expectedRevision":"%s"}
                                """.formatted(firstRevision)))
                .andExpect(status().isOk()).andReturn().getResponse();
        String latestRevision = new JsonMapper()
                .readTree(edited.getContentAsByteArray()).path("data").path("revision").asString();

        mvc.perform(post(note + "/signature").with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON).content(signature(firstRevision)))
                .andExpect(status().isConflict());
        mvc.perform(post(note + "/signature").with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON).content(signature(latestRevision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("Revised synthetic text"))
                .andExpect(jsonPath("$.data.status").value("signed"))
                .andExpect(jsonPath("$.data.signedAt").exists());
        mvc.perform(patch(note).with(doctor(fixture)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Replacement","expectedRevision":"%s"}
                                """.formatted(latestRevision)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForList("""
                SELECT operation FROM clinical_audit_logs
                WHERE entity_id = ? AND actor_user_id = ?
                """, String.class, UUID.fromString(id), fixture.actor()))
                .containsExactlyInAnyOrder("create", "update", "sign");
    }

    @Test
    void clinicalRoutesDenyOtherRolesAndClosedEncounterWrites() throws Exception {
        Fixture arrived = fixture("arrived", true);
        String path = clinicalPath(arrived) + "/notes";
        String body = "{\"content\":\"Synthetic draft\"}";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());

        for (String role : List.of("ROLE_USER", "ROLE_PATIENT", "ROLE_STAFF", "ROLE_ADMIN")) {
            mvc.perform(get(path).with(jwt().authorities(createAuthorityList(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).with(jwt().authorities(createAuthorityList(role)))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }

        mvc.perform(post(path).with(doctor(arrived))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLINICAL_ENCOUNTER_NOT_IN_PROGRESS"));
    }

    @Test
    void rollsBackClinicalWriteIfItsAuditFails() throws Exception {
        Fixture fixture = fixture(
                "in_progress",
                true,
                UUID.fromString("00000000-0000-0000-0000-00000000e004")
        );
        mvc.perform(post(clinicalPath(fixture) + "/notes").with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Synthetic draft\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CLINICAL_DOCUMENTATION_FAILED"));
        assertThat(count("clinical_notes", "encounter_id", fixture.encounter())).isZero();

        UUID code = diagnosisCode(true);

        mvc.perform(post(clinicalPath(fixture) + "/diagnoses")
                        .with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diagnosisBody(code, true))
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CLINICAL_DOCUMENTATION_FAILED"));

        assertThat(count("encounter_diagnoses", "encounter_id", fixture.encounter())).isZero();
        assertThat(count("diagnosis_histories", "actor_user_id", fixture.actor())).isZero();
    }

    @Test
    void searchesActiveCodesAndRecordsOneAuditedPrimaryDiagnosisForTheAssignedDoctor()
            throws Exception {
        Fixture visit = fixture("in_progress", true);
        Fixture other = fixture("in_progress", true);

        UUID code = diagnosisCode(true);
        UUID inactive = diagnosisCode(false);

        String catalog = "/api/v1/hospital/diagnosis-codes";
        String endpoint = clinicalPath(visit) + "/diagnoses";

        mvc.perform(get(catalog).with(doctor(visit)).param("q", code.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(code.toString()));
        mvc.perform(get(catalog).with(doctor(visit)).param("q", inactive.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get(catalog)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).with(jwt().authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(endpoint).with(doctor(other))).andExpect(status().isNotFound());

        mvc.perform(post(endpoint).with(doctor(visit)).contentType(MediaType.APPLICATION_JSON)
                        .content(diagnosisBody(inactive, true)))
                .andExpect(status().isNotFound());
        mvc.perform(post(endpoint).with(doctor(visit)).contentType(MediaType.APPLICATION_JSON)
                        .content(diagnosisBody(code, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.diagnosingDoctorId").value(visit.doctor().toString()))
                .andExpect(jsonPath("$.data.primary").value(true));
        mvc.perform(post(endpoint).with(doctor(visit)).contentType(MediaType.APPLICATION_JSON)
                        .content(diagnosisBody(code, false)))
                .andExpect(status().isConflict());
        mvc.perform(post(endpoint).with(doctor(visit)).contentType(MediaType.APPLICATION_JSON)
                        .content(diagnosisBody(diagnosisCode(true), true)))
                .andExpect(status().isConflict());
        mvc.perform(get(endpoint).with(doctor(visit)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code.id").value(code.toString()));

        assertThat(count("encounter_diagnoses", "encounter_id", visit.encounter())).isEqualTo(1);
        assertThat(count("diagnosis_histories", "actor_user_id", visit.actor())).isEqualTo(1);
        assertThat(count("clinical_audit_logs", "actor_user_id", visit.actor())).isEqualTo(1);
        assertThat(count("medical_record_access_logs", "actor_user_id", visit.actor())).isEqualTo(1);
    }

    private UUID diagnosisCode(boolean active) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO diagnosis_codes (id, coding_system, system_version, code,
                    description, is_active) VALUES (?, 'SYNTHETIC', '1', ?, 'Test code', ?)
                """, id, id.toString(), active);

        return id;
    }

    private static String diagnosisBody(UUID code, boolean primary) {
        return """
                {"diagnosisCodeId":"%s","primary":%s,"clinicalDescription":"Synthetic diagnosis"}
                """.formatted(code, primary);
    }

    @Test
    void concurrentDraftEditAndSignatureCommitOnlyOneReviewedRevision() throws Exception {
        Fixture fixture = fixture("in_progress", true);

        JsonNode draft = createDraft(fixture);

        String path = clinicalPath(fixture) + "/notes/" + draft.path("id").asString();
        String revision = draft.path("revision").asString();

        var start = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);

        try {
            var signature = threads.submit(() -> {
                start.await();
                return mvc.perform(post(path + "/signature").with(doctor(fixture))
                                .contentType(MediaType.APPLICATION_JSON).content(signature(revision)))
                        .andReturn().getResponse().getStatus();
            });
            var edit = threads.submit(() -> {
                start.await();
                return mvc.perform(patch(path).with(doctor(fixture))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"content":"Concurrent edit","expectedRevision":"%s"}
                                        """.formatted(revision)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();

            assertThat(List.of(
                    signature.get(10, TimeUnit.SECONDS),
                    edit.get(10, TimeUnit.SECONDS)
            ))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            threads.shutdownNow();
        }

        assertThat(count("clinical_audit_logs", "actor_user_id", fixture.actor())).isEqualTo(2);
    }

    private JsonNode createDraft(Fixture fixture) throws Exception {
        var result = mvc.perform(post(clinicalPath(fixture) + "/notes").with(doctor(fixture))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Synthetic draft\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();

        return new JsonMapper()
                .readTree(result.getContentAsByteArray()).path("data");
    }

    private static String signature(String revision) {
        return "{\"expectedRevision\":\"%s\"}".formatted(revision);
    }

    private static String clinicalPath(Fixture fixture) {
        return "/api/v1/hospital/encounters/" + fixture.encounter();
    }

    private static RequestPostProcessor doctor(Fixture fixture) {
        return jwt().jwt(token -> token.subject(fixture.actor().toString()))
                .authorities(createAuthorityList("ROLE_DOCTOR"));
    }

    @Test
    void deniesUnauthorizedRolesAndPreventsCrossDoctorChanges() throws Exception {
        Fixture fixture = fixture("arrived", true);
        Fixture other = fixture("arrived", true);
        String root = "/api/v1/hospital/encounters";
        String detail = root + "/" + fixture.encounter();
        String changePath = detail + "/status";

        for (String path : List.of(root, root + "/assigned", detail)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
            for (String role : List.of("ROLE_USER", "ROLE_PATIENT", "ROLE_ADMIN")) {
                mvc.perform(get(path).with(jwt().authorities(createAuthorityList(role))))
                        .andExpect(status().isForbidden());
            }
        }

        mvc.perform(get(root).with(jwt().authorities(createAuthorityList("ROLE_DOCTOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(root + "/assigned")
                        .with(jwt().authorities(createAuthorityList("ROLE_STAFF"))))
                .andExpect(status().isForbidden());

        String begin = "{\"expectedStatus\":\"arrived\",\"status\":\"in_progress\"}";
        mvc.perform(patch(changePath).contentType(MediaType.APPLICATION_JSON).content(begin))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(changePath).header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(begin))
                .andExpect(status().isUnauthorized());

        for (String role : List.of("ROLE_USER", "ROLE_PATIENT", "ROLE_ADMIN", "ROLE_STAFF")) {
            mvc.perform(patch(changePath)
                            .with(jwt().jwt(token -> token.subject(other.actor().toString()))
                                    .authorities(createAuthorityList(role)))
                            .contentType(MediaType.APPLICATION_JSON).content(begin))
                    .andExpect(status().isForbidden());
        }

        mvc.perform(patch(changePath)
                        .with(jwt().jwt(token -> token.subject(other.actor().toString()))
                                .authorities(createAuthorityList("ROLE_DOCTOR")))
                        .contentType(MediaType.APPLICATION_JSON).content(begin))
                .andExpect(status().isNotFound());
        assertError(ErrorCode.ENCOUNTER_ACCESS_DENIED, () -> encounters.changeStatus(
                fixture.encounter(),
                request("arrived", "cancelled", "Test"),
                authentication(fixture.actor().toString(), "ROLE_DOCTOR")
        ));
        assertNoEvents(fixture);
    }

    @Test
    void rollsBackStatusTimestampsHistoryAndAuditWhenEitherEventInsertFails() {
        for (UUID actor : List.of(HISTORY_FAILURE, AUDIT_FAILURE)) {
            Fixture fixture = fixture("arrived", true, actor);

            Timestamp originalUpdated = timestamp(fixture.encounter(), "updated_at");

            assertError(
                    ErrorCode.ENCOUNTER_CHANGE_FAILED,
                    () -> encounters.changeStatus(
                            fixture.encounter(),
                            request("arrived", "in_progress", null),
                            authentication(actor.toString(), "ROLE_DOCTOR")
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
    void serializesCompetingArrivalAndCancellationOrStart() throws Exception {
        for (String initial : List.of("scheduled", "arrived")) {
            Fixture fixture = fixture(initial, true);
            String alternate = initial.equals("scheduled") ? "arrived" : "in_progress";
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> compete(fixture, initial, alternate, start));
                var second = executor.submit(() -> compete(fixture, initial, "cancelled", start));
                start.countDown();

                assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("committed", "ENCOUNTER_STATUS_CONFLICT");
            } finally {
                executor.shutdownNow();
            }

            assertThat(currentStatus(fixture)).isIn(alternate, "cancelled");
            assertThat(count("encounter_status_histories", "encounter_id", fixture.encounter()))
                    .isEqualTo(1);
            assertThat(count("clinical_audit_logs", "entity_id", fixture.encounter())).isEqualTo(1);
        }
    }

    private String compete(Fixture fixture, String initial, String target, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            change(fixture, initial, target, target.equals("cancelled") ? "Test" : null);
            return "committed";
        } catch (BusinessException failure) {
            return failure.getErrorCode().name();
        }
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
                                .authorities(createAuthorityList("ROLE_STAFF"))
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
                                .authorities(createAuthorityList("ROLE_STAFF"))
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
                                    : "ENCOUNTER_NOT_FOUND"
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
        return fixture(status, assignedDoctor, UUID.randomUUID());
    }

    private Fixture fixture(String status, boolean assignedDoctor, UUID actor) {
        UUID patient = UUID.randomUUID();
        UUID doctor = UUID.randomUUID();
        UUID encounter = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO patients (id, medical_record_number, given_name) VALUES (?, ?, ?)",
                patient, "P-" + patient, "Synthetic encounter patient"
        );

        jdbc.update("""
                INSERT INTO doctors (
                    id, external_user_id, license_authority, license_number, given_name
                ) VALUES (?, ?, 'TEST_ONLY', ?, 'Synthetic doctor')
                """, doctor, actor, doctor.toString());
        given(accounts.getAccountIdentity(actor))
                .willReturn(new UserServiceClient.AccountIdentity(actor, true, 4));

        Instant started = List.of("in_progress", "completed").contains(status) ? NOW : null;
        Instant ended = status.equals("completed") ? NOW : null;

        jdbc.update("""
                        INSERT INTO medical_encounters
                            (id, encounter_number, patient_id, doctor_id, status,
                            started_at, ended_at, cancellation_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, encounter, "E-" + encounter, patient, assignedDoctor ? doctor : null, status,
                started == null ? null : Timestamp.from(started),
                ended == null ? null : Timestamp.from(ended),
                status.equals("cancelled") ? "Test fixture" : null);

        return new Fixture(encounter, patient, doctor, actor);
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
                authentication(fixture.actor().toString(),
                        target.equals("in_progress") || target.equals("completed")
                                ? "ROLE_DOCTOR" : "ROLE_STAFF")
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
            return Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
