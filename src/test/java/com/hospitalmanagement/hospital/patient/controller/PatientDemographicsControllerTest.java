package com.hospitalmanagement.hospital.patient.controller;

import com.hospitalmanagement.hospital.common.exception.UnreadableRequestDetails;
import com.hospitalmanagement.hospital.patient.dto.response.PatientDemographicsResponse;
import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.impl.PatientDemographicsServiceImpl;
import com.hospitalmanagement.hospital.security.config.UserSecurityConfig;
import com.hospitalmanagement.hospital.security.jwt.user.JwtRoleConverter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientDemographicsController.class)
@Import({
        JwtRoleConverter.class,
        PatientDemographicsControllerTest.TestTransactionManager.class,
        PatientDemographicsServiceImpl.class,
        UnreadableRequestDetails.class,
        UserSecurityConfig.class
})
@EnableTransactionManagement
class PatientDemographicsControllerTest {
    private static final String PATH = "/api/v1/hospital/patients/";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer example";
    private static final UUID STAFF_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PATIENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final Logger workflowLogger =
            (Logger) LoggerFactory.getLogger(PatientDemographicsServiceImpl.class);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private PatientRepository patients;

    @MockitoBean
    private MedicalRecordAccessLogRepository accessLogs;

    @BeforeEach
    void captureWorkflowLogs() {
        events.start();
        workflowLogger.addAppender(events);
    }

    @AfterEach
    void stopCapturingWorkflowLogs() {
        workflowLogger.detachAppender(events);
        events.stop();
    }

    @Test
    void staffCanReadOnlyDemographicsAndAccessIsAudited() throws Exception {
        authenticate(3);
        given(patients.findDemographicsByIdAndDeletedAtIsNull(PATIENT_ID))
                .willReturn(Optional.of(demographics()));

        mvc.perform(get(PATH + PATIENT_ID).header(AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.id").value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.data.medicalRecordNumber").value("PAT-TEST"))
                .andExpect(jsonPath("$.data.sexAtBirth").value("unknown"))
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist())
                .andExpect(jsonPath("$.data.diagnoses").doesNotExist())
                .andExpect(jsonPath("$.data.labResults").doesNotExist())
                .andExpect(jsonPath("$.data.prescriptions").doesNotExist());

        var event = ArgumentCaptor.forClass(MedicalRecordAccessLog.class);
        verify(accessLogs).saveAndFlush(event.capture());

        assertThat(event.getValue().getActorUserId()).isEqualTo(STAFF_ID);
        assertThat(event.getValue().getEntityId()).isEqualTo(PATIENT_ID);
        assertThat(event.getValue().getOperation()).isEqualTo(RecordAccessOperation.VIEW);
        assertThat(event.getValue().getCorrelationId()).isNotNull();
        assertThat(events.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.INFO);
        assertThat(events.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Patient demographics lookup started",
                        "Patient demographics lookup committed"
                );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 5, 9})
    void deniesRolesWithoutRoutePermission(int role) throws Exception {
        authenticate(role);

        mvc.perform(get(PATH + PATIENT_ID).header(AUTHORIZATION, BEARER))
                .andExpect(status().isForbidden());

        verifyNoInteractions(patients, accessLogs);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH + PATIENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesPatientUuid() throws Exception {
        authenticate(3);

        mvc.perform(get(PATH + "invalid").header(AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(patients, accessLogs);
    }

    @Test
    void returnsNotFoundForMissingOrInactivePatient() throws Exception {
        authenticate(3);

        mvc.perform(get(PATH + PATIENT_ID).header(AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound());

        verifyNoInteractions(accessLogs);
    }

    @Test
    void failsClosedWhenAuditCannotBeWritten() throws Exception {
        authenticate(3);
        given(patients.findDemographicsByIdAndDeletedAtIsNull(PATIENT_ID))
                .willReturn(Optional.of(demographics()));
        given(accessLogs.saveAndFlush(any(MedicalRecordAccessLog.class)))
                .willThrow(new DataAccessResourceFailureException("fixture"));

        mvc.perform(get(PATH + PATIENT_ID).header(AUTHORIZATION, BEARER))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESS_LOG_FAILED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(events.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Patient demographics lookup started",
                        "Patient demographics access logging failed"
                );
    }

    private void authenticate(int role) {
        Jwt token = Jwt.withTokenValue("example")
                .header("alg", "RS256")
                .subject(STAFF_ID.toString())
                .claim("role", role)
                .build();

        given(decoder.decode("example")).willReturn(token);
    }

    private PatientDemographicsResponse demographics() {
        return new PatientDemographicsResponse(
                PATIENT_ID,
                "PAT-TEST",
                "An",
                "Example",
                LocalDate.of(1995, 6, 15),
                SexAtBirth.UNKNOWN,
                Instant.parse("2026-11-19T07:00:00Z")
        );
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
