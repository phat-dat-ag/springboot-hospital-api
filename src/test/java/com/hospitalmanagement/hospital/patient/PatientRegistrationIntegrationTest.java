package com.hospitalmanagement.hospital.patient;

import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.service.PatientService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import jakarta.validation.ConstraintViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses a dedicated disposable PostgreSQL database; never starts or resets Docker.
 */
@EnabledIfEnvironmentVariable(named = "CLINICAL_REGISTRATION_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "spring.flyway.default-schema=public",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.baseline-on-migrate=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientRegistrationIntegrationTest {
    private static final String PATH = "/api/v1/hospital/patients";
    private static final UUID ACTOR = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PatientService patients;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url = Objects.requireNonNull(System.getenv("CLINICAL_REGISTRATION_JDBC_URL"));

        if (!url.matches("jdbc:postgresql://[^/]+/clinical_registration_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Use the dedicated clinical_registration_test DB");
        }

        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () ->
                Objects.requireNonNull(System.getenv("CLINICAL_REGISTRATION_DB_USER")));
        properties.add("spring.datasource.password", () ->
                Objects.requireNonNull(System.getenv("CLINICAL_REGISTRATION_DB_PASSWORD")));
    }

    @BeforeAll
    void installTestOnlyFailureInjection() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_registration_failure() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM patients WHERE id = NEW.patient_id
                        AND medical_record_number LIKE TG_ARGV[0] || '%') THEN
                        RAISE EXCEPTION 'Injected registration failure' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_contact_failure BEFORE INSERT ON patient_contacts
                FOR EACH ROW EXECUTE FUNCTION test_registration_failure('FAIL-CONTACT-')
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_audit_failure BEFORE INSERT ON clinical_audit_logs
                FOR EACH ROW EXECUTE FUNCTION test_registration_failure('FAIL-AUDIT-')
                """);
    }

    @Test
    void registersPatientWithoutUserAccountOrContacts() throws Exception {
        String mrn = mrn("NO-CONTACT");

        var result = register(mrn, "[]").andExpect(status().isCreated());

        UUID patientId = patientId(mrn);

        OffsetDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM patients WHERE id = ?",
                OffsetDateTime.class,
                patientId
        );

        result.andExpect(jsonPath("$.data.id").value(patientId.toString()))
                .andExpect(jsonPath("$.data.medicalRecordNumber").value(mrn))
                .andExpect(jsonPath("$.data.sexAtBirth").value("female"))
                .andExpect(jsonPath("$.data.createdAt").value(createdAt.toInstant().toString()))
                .andExpect(jsonPath("$.data.contactIds").isEmpty());

        assertThat(jdbc.queryForObject(
                "SELECT external_user_id FROM patients WHERE id = ?",
                UUID.class,
                patientId
        )).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT sex_at_birth FROM patients WHERE id = ?",
                String.class,
                patientId
        )).isEqualTo("female");
        assertAudit(patientId);
    }

    @Test
    void persistsTwoContactsAndOnlyAuditMetadata() throws Exception {
        String mrn = mrn("CONTACTS");

        var result = register(mrn, """
                [
                  {"contactType":"self","phone":"test-phone","isPrimary":true},
                  {"contactType":"emergency","contactName":"Example guardian",
                   "email":"example@example.invalid","isPrimary":true}
                ]
                """).andExpect(status().isCreated());

        UUID patientId = patientId(mrn);

        List<UUID> contactIds = jdbc.queryForList(
                "SELECT id FROM patient_contacts WHERE patient_id = ?",
                UUID.class,
                patientId
        );

        assertThat(contactIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList(
                "SELECT contact_type FROM patient_contacts WHERE patient_id = ?",
                String.class,
                patientId
        )).containsExactlyInAnyOrder("self", "emergency");

        result.andExpect(jsonPath("$.data.contactIds.length()").value(2));

        for (int index = 0; index < 2; index++) {
            result.andExpect(jsonPath("$.data.contactIds[" + index + "]").isNotEmpty());
        }

        assertAudit(patientId);
    }

    @Test
    void rejectsInvalidDemographicsWithoutWriting() throws Exception {
        Map<String, Integer> before = rowCounts();

        mvc.perform(post(PATH)
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))
                                .authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicalRecordNumber":"INVALID","givenName":" ",
                                 "dateOfBirth":"9999-01-01"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(rowCounts()).isEqualTo(before);
    }

    @Test
    void rejectsCrossFieldContactErrorsBeforeInserting() throws Exception {
        Map<String, Integer> before = rowCounts();

        register(mrn("NO-DETAILS"), """
                [{"contactType":"self","phone":"  ","addressText":"  "}]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTACT_DETAILS_REQUIRED"));
        register(mrn("NO-NAME"), """
                [{"contactType":"emergency","phone":"test-phone","contactName":" "}]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTACT_NAME_REQUIRED"));
        register(mrn("TWO-PRIMARY"), """
                [{"contactType":"self","phone":"a","isPrimary":true},
                 {"contactType":"self","phone":"b","isPrimary":true}]
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRIMARY_CONTACT"));

        assertThat(rowCounts()).isEqualTo(before);
    }

    @Test
    void duplicateMrnRemainsReservedAfterSoftDeletion() throws Exception {
        String mrn = mrn("DUPLICATE");

        register(mrn, "[]").andExpect(status().isCreated());

        Map<String, Integer> before = rowCounts();

        register(mrn, "[]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATIENT_ALREADY_EXISTS"));

        jdbc.update(
                "UPDATE patients SET deleted_at = clock_timestamp() WHERE id = ?",
                patientId(mrn)
        );

        register(mrn, "[]").andExpect(status().isConflict());

        assertThat(rowCounts()).isEqualTo(before);
    }

    @Test
    void rollsBackPatientContactsAndAuditWhenAnyInsertFails() throws Exception {
        for (String prefix : List.of("FAIL-CONTACT", "FAIL-AUDIT")) {
            Map<String, Integer> before = rowCounts();

            register(mrn(prefix), """
                    [{"contactType":"self","phone":"test-phone"}]
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(result -> assertThat(result.getResolvedException())
                            .isInstanceOf(DataIntegrityViolationException.class));

            assertThat(rowCounts()).isEqualTo(before);
        }
    }

    @Test
    void concurrentSameMrnRequestsCommitExactlyOneRegistration() throws Exception {
        String mrn = mrn("CONCURRENT");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Integer> task = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start registration");
                }

                return register(mrn, "[]").andReturn().getResponse().getStatus();
            };

            var first = executor.submit(task);
            var second = executor.submit(task);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM patients WHERE medical_record_number = ?",
                    Integer.class,
                    mrn
            )).isEqualTo(1);

            assertAudit(patientId(mrn));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validatesDirectServiceCallsAsWellAsHttpRequests() {
        RegisterPatientRequest invalid = new RegisterPatientRequest(
                " ",
                " ",
                null,
                null,
                null,
                null
        );

        Map<String, Integer> before = rowCounts();

        assertThatThrownBy(() -> patients.registerPatient(invalid, ACTOR.toString()))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(rowCounts()).isEqualTo(before);
    }

    private ResultActions register(String mrn, String contacts) throws Exception {
        String body = """
                {"medicalRecordNumber":"%s","givenName":"Example",
                 "sexAtBirth":"female","contacts":%s}
                """.formatted(mrn, contacts);

        return mvc.perform(post(PATH)
                .with(jwt().jwt(token -> token.subject(ACTOR.toString()))
                        .authorities(createAuthorityList("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID patientId(String mrn) {
        return jdbc.queryForObject(
                "SELECT id FROM patients WHERE medical_record_number = ?",
                UUID.class,
                mrn
        );
    }

    private void assertAudit(UUID patientId) {
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT * FROM clinical_audit_logs WHERE patient_id = ?",
                patientId
        );

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .containsEntry("actor_user_id", ACTOR)
                .containsEntry("actor_type", "user")
                .containsEntry("operation", "create")
                .containsEntry("entity_type", "patients")
                .containsEntry("entity_id", patientId);
        assertThat(events.get(0).get("occurred_at")).isNotNull();
    }

    private Map<String, Integer> rowCounts() {
        return Map.of(
                "patients", jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class),
                "contacts", jdbc.queryForObject(
                        "SELECT count(*) FROM patient_contacts", Integer.class
                ),
                "audit", jdbc.queryForObject(
                        "SELECT count(*) FROM clinical_audit_logs", Integer.class
                )
        );
    }

    private String mrn(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 20);
    }
}
