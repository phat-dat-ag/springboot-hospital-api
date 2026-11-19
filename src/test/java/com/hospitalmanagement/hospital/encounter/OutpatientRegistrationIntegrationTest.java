package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.client.UserServiceClient;
import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.dto.request.RegisterEncounterRequest;
import com.hospitalmanagement.hospital.encounter.registration.EncounterRegistrationService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "CLINICAL_ENCOUNTER_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class OutpatientRegistrationIntegrationTest {
    private static final String VISITS = "/api/v1/hospital/encounters";
    private static final String DOCTORS = "/api/v1/hospital/doctors";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Clock clinicClock;

    @Autowired
    private EncounterRegistrationService registrations;

    @MockitoBean
    private UserServiceClient accounts;

    @MockitoBean
    private JwtDecoder decoder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = Objects.requireNonNull(System.getenv("CLINICAL_ENCOUNTER_JDBC_URL"));

        if (!url.matches("jdbc:postgresql://[^/]+/clinical_encounter_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Use the dedicated clinical_encounter_test database");
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CLINICAL_ENCOUNTER_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CLINICAL_ENCOUNTER_DB_PASSWORD"));
    }

    @Test
    void requiresStaffAndValidBearerForBothRoutes() throws Exception {
        mvc.perform(get(DOCTORS)).andExpect(status().isUnauthorized());
        mvc.perform(post(VISITS)).andExpect(status().isUnauthorized());
        given(decoder.decode("invalid")).willThrow(new JwtException("Invalid token"));
        mvc.perform(get(DOCTORS).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(VISITS).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());

        for (int role : new int[]{1, 2, 4, 5}) {
            String bearer = bearer(role, UUID.randomUUID());

            mvc.perform(get(DOCTORS).header("Authorization", bearer))
                    .andExpect(status().isForbidden());
            mvc.perform(post(VISITS).header("Authorization", bearer))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(accounts);
    }

    @Test
    void verifiesShortPageAndContinuesWithoutSkippingOrDuplicatingCandidates() throws Exception {
        String name = "selection-" + UUID.randomUUID();
        String bearer = bearer(3, UUID.randomUUID());
        long group = UUID.randomUUID().getMostSignificantBits();

        for (int number = 1; number <= 20; number++) {
            UUID account = UUID.randomUUID();
            doctor(new UUID(group, number), account, name, null);
            given(accounts.getAccountIdentity(account))
                    .willReturn(new UserServiceClient.AccountIdentity(account, false, 4));
        }

        UUID first = new UUID(group, 21);
        UUID second = new UUID(group, 22);
        doctor(first, eligibleAccount(), name, LocalDate.now(clinicClock));
        doctor(second, eligibleAccount(), name, null);
        doctor(new UUID(group, 23), UUID.randomUUID(), name, LocalDate.now(clinicClock).minusDays(1));
        doctor(new UUID(group, 24), null, name, null);

        mvc.perform(get(DOCTORS).header("Authorization", bearer).param("name", name.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(new UUID(group, 20).toString()));

        mvc.perform(get(DOCTORS).header("Authorization", bearer)
                        .param("name", name)
                        .param("size", "1")
                        .param("after", new UUID(group, 20).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value(first.toString()));

        mvc.perform(get(DOCTORS).header("Authorization", bearer)
                        .param("name", name).param("after", first.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void refusesUnavailableOrMalformedEligibilityAndOmitsConfirmedMissingAccounts() throws Exception {
        UUID account = UUID.randomUUID();
        String name = "dependency-" + UUID.randomUUID();
        doctor(UUID.randomUUID(), account, name, null);
        var request = get(DOCTORS).header("Authorization", bearer(3, UUID.randomUUID()))
                .param("name", name);

        given(accounts.getAccountIdentity(account)).willThrow(
                new BusinessException(ErrorCode.DOCTOR_ACCOUNT_NOT_FOUND),
                new BusinessException(ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE),
                new BusinessException(ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE)
        );
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(request).andExpect(status().isServiceUnavailable());
        mvc.perform(request).andExpect(status().isBadGateway());
    }

    @Test
    void rejectsInvalidRequestAndClientSelectedEncounterFields() throws Exception {
        String staff = bearer(3, UUID.randomUUID());
        String valid = body(UUID.randomUUID(), UUID.randomUUID(), "Routine");
        String unexpected = valid.stripTrailing().replace("}", ",\"status\":\"completed\"}");

        mvc.perform(get(DOCTORS).header("Authorization", staff).param("size", "21"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(VISITS).header("Authorization", staff).contentType("application/json")
                        .content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(post(VISITS).header("Authorization", staff).header("Idempotency-Key", "key")
                        .contentType("application/json").content(unexpected))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test
    void checksPatientDoctorAccountAndClinicDateBeforeCreatingAnything() throws Exception {
        UUID patient = patient();
        UUID account = UUID.randomUUID();
        UUID doctor = doctor(UUID.randomUUID(), account, "eligibility", null);
        String staff = bearer(3, UUID.randomUUID());
        var request = post(VISITS).header("Authorization", staff)
                .header("Idempotency-Key", "eligibility").contentType("application/json");

        mvc.perform(request.content(body(UUID.randomUUID(), doctor, "Routine")))
                .andExpect(status().isNotFound());
        mvc.perform(request.content(body(patient, UUID.randomUUID(), "Routine")))
                .andExpect(status().isNotFound());
        assertThat(clinicClock.getZone().getId()).isEqualTo("Asia/Ho_Chi_Minh");
        var past = LocalDate.now(clinicClock)
                .atStartOfDay(clinicClock.getZone()).toInstant().minusMillis(1);
        var invalidSchedule = body(patient, doctor, "Routine").stripTrailing()
                .replace("}", ",\"scheduledAt\":\"" + past + "\"}");
        mvc.perform(request.content(invalidSchedule))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENCOUNTER_SCHEDULE_INVALID"));
        verifyNoInteractions(accounts);

        given(accounts.getAccountIdentity(account))
                .willReturn(new UserServiceClient.AccountIdentity(account, true, 3));
        mvc.perform(request.content(body(patient, doctor, "Routine")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCTOR_ACCOUNT_ROLE_INVALID"));
        jdbc.update("UPDATE patients SET deleted_at = now() WHERE id = ?", patient);
        mvc.perform(request.content(body(patient, doctor, "Routine")))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM medical_encounters WHERE patient_id = ?", patient))
                .isZero();
    }

    @Test
    void registersWithInitialAuditAndReplaysCanonicalPayloadWithoutNewEligibilityCall()
            throws Exception {
        UUID patient = patient();
        UUID doctor = doctor(UUID.randomUUID(), eligibleAccount(), "walk-in", null);
        UUID actor = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String staff = bearer(3, actor);

        mvc.perform(post(VISITS).header("Authorization", staff).header("Idempotency-Key", key)
                        .contentType("application/json").content(body(patient, doctor, "  Routine  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("scheduled"))
                .andExpect(jsonPath("$.data.doctorId").value(doctor.toString()))
                .andExpect(jsonPath("$.data.encounterNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.scheduledAt").isNotEmpty());

        UUID encounter = jdbc.queryForObject(
                "SELECT id FROM medical_encounters WHERE patient_id = ?", UUID.class, patient
        );
        clearInvocations(accounts);

        mvc.perform(post(VISITS).header("Authorization", staff).header("Idempotency-Key", key)
                        .contentType("application/json").content(body(patient, doctor, "Routine")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(encounter.toString()));
        mvc.perform(post(VISITS).header("Authorization", staff).header("Idempotency-Key", key)
                        .contentType("application/json").content(body(patient, doctor, "Changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCOUNTER_IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(VISITS).header("Authorization", bearer(5, actor))
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body(patient, doctor, "Routine")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accounts);
        assertThat(count("SELECT count(*) FROM medical_encounters WHERE patient_id = ?", patient))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM encounter_registration_keys WHERE encounter_id = ?",
                encounter)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM encounter_status_histories
                WHERE encounter_id = ? AND actor_user_id = ?
                    AND from_status IS NULL AND to_status = 'scheduled'
                """, encounter, actor)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM clinical_audit_logs
                WHERE entity_id = ? AND patient_id = ? AND actor_user_id = ? AND operation = 'create'
                """, encounter, patient, actor)).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalCommandsCommitOneEncounterAndOneResult() throws Exception {
        UUID patient = patient();
        UUID doctor = doctor(UUID.randomUUID(), eligibleAccount(), "concurrency", null);
        String actor = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        var request = new RegisterEncounterRequest(patient, doctor, "Routine", null);
        var start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return registrations.register(actor, key, request);
            });
            var second = executor.submit(() -> {
                start.await();
                return registrations.register(actor, key, request);
            });
            start.countDown();

            var left = first.get(15, TimeUnit.SECONDS);
            var right = second.get(15, TimeUnit.SECONDS);
            assertThat(left.encounter()).isEqualTo(right.encounter());
            assertThat(left.replay()).isNotEqualTo(right.replay());
            assertThat(count("SELECT count(*) FROM medical_encounters WHERE patient_id = ?", patient))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsOpenSameDayVisitAndAcceptsLaterClinicDate() {
        UUID patient = patient();
        UUID doctor = doctor(UUID.randomUUID(), eligibleAccount(), "duplicate check", null);
        String actor = UUID.randomUUID().toString();
        var request = new RegisterEncounterRequest(patient, doctor, "Routine", null);
        var first = registrations.register(actor, "first", request);

        assertThatThrownBy(() -> registrations.register(actor, "second", request))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.ENCOUNTER_OPEN_VISIT));

        var tomorrow = LocalDate.now(clinicClock).plusDays(1)
                .atStartOfDay(clinicClock.getZone()).toInstant();
        var next = registrations.register(actor, "second",
                new RegisterEncounterRequest(patient, doctor, "Routine", tomorrow));
        assertThat(next.encounter().encounterNumber())
                .isNotEqualTo(first.encounter().encounterNumber());
    }

    @Test
    void concurrentDistinctCommandsDoNotBypassSameDayConflictPolicy() throws Exception {
        UUID patient = patient();
        UUID doctor = doctor(UUID.randomUUID(), eligibleAccount(), "parallel visits", null);
        String actor = UUID.randomUUID().toString();
        var request = new RegisterEncounterRequest(patient, doctor, "Routine", null);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> concurrentOutcome(actor, "one", request, start));
            var second = executor.submit(() -> concurrentOutcome(actor, "two", request, start));
            start.countDown();

            assertThat(List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder("created", "ENCOUNTER_OPEN_VISIT");
            assertThat(count("SELECT count(*) FROM medical_encounters WHERE patient_id = ?", patient))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private String concurrentOutcome(
            String actor,
            String key,
            RegisterEncounterRequest request,
            CountDownLatch start
    ) throws InterruptedException {
        start.await();

        try {
            assertThat(registrations.register(actor, key, request).replay()).isFalse();
            return "created";
        } catch (BusinessException failure) {
            return failure.getErrorCode().name();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"history", "audit"})
    void rollsBackEveryWriteAndAllowsRetryWhenRequiredWriteFails(String target) {
        String failName = "P02 fail " + target;
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION p02_force_registration_failure()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF TG_TABLE_NAME = 'clinical_audit_logs' THEN
                        IF EXISTS (SELECT 1 FROM patients WHERE id = NEW.patient_id
                                   AND given_name = 'P02 fail audit') THEN
                            RAISE EXCEPTION USING ERRCODE = '23514';
                        END IF;
                    ELSE
                        IF EXISTS (SELECT 1 FROM medical_encounters e
                                   JOIN patients p ON p.id = e.patient_id
                                   WHERE e.id = NEW.encounter_id
                                     AND p.given_name = 'P02 fail history') THEN
                            RAISE EXCEPTION USING ERRCODE = '23514';
                        END IF;
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER p02_fail_history BEFORE INSERT
                ON encounter_status_histories FOR EACH ROW
                EXECUTE FUNCTION p02_force_registration_failure()
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER p02_fail_audit BEFORE INSERT
                ON clinical_audit_logs FOR EACH ROW
                EXECUTE FUNCTION p02_force_registration_failure()
                """);
        UUID patient = patient();
        jdbc.update("UPDATE patients SET given_name = ? WHERE id = ?", failName, patient);
        UUID doctor = doctor(UUID.randomUUID(), eligibleAccount(), "rollback", null);
        UUID actor = UUID.randomUUID();
        var command = new RegisterEncounterRequest(patient, doctor, "Routine", null);

        assertThatThrownBy(() -> registrations.register(actor.toString(), "retry", command))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.ENCOUNTER_REGISTRATION_FAILED));
        assertThat(count("SELECT count(*) FROM medical_encounters WHERE patient_id = ?", patient))
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM encounter_registration_keys WHERE actor_user_id = ?
                """, actor)).isZero();
        assertThat(count("SELECT count(*) FROM clinical_audit_logs WHERE patient_id = ?", patient))
                .isZero();

        jdbc.update("UPDATE patients SET given_name = 'Synthetic' WHERE id = ?", patient);
        assertThat(registrations.register(actor.toString(), "retry", command).replay()).isFalse();
    }

    private String bearer(int role, UUID actor) {
        String token = "fixture-" + UUID.randomUUID();
        given(decoder.decode(token)).willReturn(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(actor.toString())
                .claim("role", role)
                .build());
        return "Bearer " + token;
    }

    private UUID patient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, medical_record_number, given_name) VALUES (?, ?, ?)",
                id, id.toString(), "Synthetic");
        return id;
    }

    private UUID eligibleAccount() {
        UUID account = UUID.randomUUID();
        given(accounts.getAccountIdentity(account))
                .willReturn(new UserServiceClient.AccountIdentity(account, true, 4));
        return account;
    }

    private UUID doctor(UUID id, UUID account, String name, LocalDate expiry) {
        jdbc.update("""
                INSERT INTO doctors (
                    id, external_user_id, license_authority, license_number, given_name,
                    license_expires_on
                ) VALUES (?, ?, 'Synthetic', ?, ?, ?)
                """, id, account, id.toString(), name, expiry);
        return id;
    }

    private int count(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, parameters));
    }

    private static String body(UUID patient, UUID doctor, String complaint) {
        return """
                {"patientId":"%s","doctorId":"%s","chiefComplaint":"%s"}
                """.formatted(patient, doctor, complaint);
    }
}
