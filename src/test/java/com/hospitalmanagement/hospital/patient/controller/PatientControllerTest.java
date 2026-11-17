package com.hospitalmanagement.hospital.patient.controller;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.common.exception.UnreadableRequestDetails;
import com.hospitalmanagement.hospital.logging.StructuredJsonEncoder;
import com.hospitalmanagement.hospital.logging.filter.RequestLoggingFilter;
import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientRegistrationResponse;
import com.hospitalmanagement.hospital.patient.service.PatientService;
import com.hospitalmanagement.hospital.security.config.UserSecurityConfig;
import com.hospitalmanagement.hospital.security.jwt.user.JwtRoleConverter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientController.class)
@Import({UserSecurityConfig.class, JwtRoleConverter.class, UnreadableRequestDetails.class})
class PatientControllerTest {
    private static final String PATH = "/api/v1/hospital/patients";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String BODY = """
            {"medicalRecordNumber":"LEARN-0001","givenName":"Example"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PatientService patients;

    @MockitoBean
    private JwtDecoder decoder;

    @Test
    void returnsCreatedEnvelopeAndUsesAuthenticatedActor() throws Exception {
        UUID patientId = UUID.randomUUID();

        given(patients.registerPatient(any(), eq(ACTOR.toString())))
                .willReturn(new PatientRegistrationResponse(
                        patientId,
                        "LEARN-0001",
                        "Example",
                        null,
                        null,
                        null,
                        Instant.parse("2026-11-17T00:00:00Z"),
                        List.of()
                ));

        mvc.perform(post(PATH)
                        .with(
                                jwt()
                                        .jwt(token -> token.subject(ACTOR.toString()))
                                        .authorities(createAuthorityList("ROLE_ADMIN"))
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("PATIENT_REGISTERED"))
                .andExpect(jsonPath("$.data.id").value(patientId.toString()))
                .andExpect(jsonPath("$.data.contactIds").isEmpty())
                .andExpect(jsonPath("$.meta.timestamp").exists());

        verify(patients).registerPatient(any(RegisterPatientRequest.class), eq(ACTOR.toString()));
    }

    @Test
    void requiresAuthenticationAndAdminIncludingTrailingSlash() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        for (String path : List.of(PATH, PATH + "/")) {
            mvc.perform(post(path)
                            .with(jwt().authorities(createAuthorityList("ROLE_USER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(patients);
    }

    @Test
    void rejectsInvalidBearerTokenAndNonUuidActor() throws Exception {
        given(decoder.decode("bad-token")).willThrow(new JwtException("Invalid token"));

        mvc.perform(post(PATH)
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(patients);

        given(patients.registerPatient(any(), eq("not-a-uuid")))
                .willThrow(new BusinessException(ErrorCode.INVALID_ACTOR_ID));
        mvc.perform(post(PATH)
                        .with(
                                jwt().
                                        jwt(token -> token.subject("not-a-uuid"))
                                        .authorities(createAuthorityList("ROLE_ADMIN"))
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACTOR_ID"));
        verify(patients).registerPatient(any(RegisterPatientRequest.class), eq("not-a-uuid"));
    }

    @Test
    void rejectsInvalidRequestWithExplicitValidationMessages() throws Exception {
        mvc.perform(post(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicalRecordNumber":"LEARN-0001","givenName":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Given name is required"));

        verifyNoInteractions(patients);
    }

    @Test
    void invalidEnumReportsSafeFieldDetailsInResponseAndCompletionLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        var encoder = new StructuredJsonEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.start();

        try {
            mvc.perform(post(PATH)
                            .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                            .header("X-Correlation-ID", "enum-validation-test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"medicalRecordNumber":"private-mrn","givenName":"private-name",
                                     "sexAtBirth":"private-rejected-value"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
                    .andExpect(jsonPath("$.error.details[0].field").value("sexAtBirth"))
                    .andExpect(jsonPath("$.error.details[0].message")
                            .value("Must be one of: female, male, intersex, unknown"))
                    .andExpect(jsonPath("$.meta.correlationId").value("enum-validation-test"));

            assertThat(appender.list).hasSize(1);
            var event = new ObjectMapper().readTree(encoder.encode(appender.list.get(0)));
            assertThat(event.path("errorCode").asText()).isEqualTo("INVALID_ENUM_VALUE");
            assertThat(event.path("validation.field").asText()).isEqualTo("sexAtBirth");
            assertThat(event.path("validation.allowed_values").toString())
                    .isEqualTo("[\"female\",\"male\",\"intersex\",\"unknown\"]");
            assertThat(event.path("correlationId").asText()).isEqualTo("enum-validation-test");
            assertThat(event.toString())
                    .doesNotContain("private-mrn", "private-name", "private-rejected-value");
            assertThat(event.has("stack_trace")).isFalse();
            verifyNoInteractions(patients);
        } finally {
            encoder.stop();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void invalidContactEnumIdentifiesTheNestedField() throws Exception {
        mvc.perform(post(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicalRecordNumber":"LEARN-0001","givenName":"Example",
                                 "contacts":[{"contactType":"invalid","phone":"test-phone"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
                .andExpect(jsonPath("$.error.details[0].field").value("contacts[0].contactType"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Must be one of: self, emergency, guardian"));
        verifyNoInteractions(patients);
    }

    @Test
    void malformedJsonRetainsSafeGenericError() throws Exception {
        mvc.perform(post(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicalRecordNumber\":\"private-value\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error").doesNotExist());
        verifyNoInteractions(patients);
    }

    @Test
    void returnsSafeConflictForDuplicateMrn() throws Exception {
        given(patients.registerPatient(any(), any()))
                .willThrow(new BusinessException(ErrorCode.PATIENT_ALREADY_EXISTS));

        mvc.perform(post(PATH)
                        .with(
                                jwt()
                                        .jwt(token -> token.subject(ACTOR.toString()))
                                        .authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PATIENT_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message")
                        .value("Medical record number is already registered"));
    }
}
