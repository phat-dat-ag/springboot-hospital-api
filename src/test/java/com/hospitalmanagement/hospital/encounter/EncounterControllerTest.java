package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.common.exception.UnreadableRequestDetails;
import com.hospitalmanagement.hospital.encounter.controller.EncounterController;
import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterStatusResponse;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.service.EncounterStatusService;
import com.hospitalmanagement.hospital.security.config.UserSecurityConfig;
import com.hospitalmanagement.hospital.security.jwt.user.JwtRoleConverter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EncounterController.class)
@Import({UserSecurityConfig.class, JwtRoleConverter.class, UnreadableRequestDetails.class})
class EncounterControllerTest {
    private static final UUID ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/hospital/encounters/" + ID + "/status";
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String BODY =
            "{\"expectedStatus\":\"scheduled\",\"status\":\"arrived\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EncounterStatusService encounters;

    @MockitoBean
    private JwtDecoder decoder;

    @Test
    void returnsDtoWithLowercaseStatusAndPassesAuthenticatedSubject() throws Exception {
        UUID patient = UUID.randomUUID();

        Instant updated = Instant.parse("2026-11-18T00:00:00Z");

        given(encounters.changeStatus(eq(ID), any(), eq(ACTOR)))
                .willReturn(new EncounterStatusResponse(
                        ID, patient, EncounterStatus.ARRIVED, null, null, updated
                ));

        mvc.perform(patch(PATH)
                        .with(jwt().jwt(token -> token.subject(ACTOR))
                                .authorities(createAuthorityList("ROLE_ADMIN"))
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(ID.toString()))
                .andExpect(jsonPath("$.data.patientId").value(patient.toString()))
                .andExpect(jsonPath("$.data.status").value("arrived"))
                .andExpect(jsonPath("$.data.updatedAt").value(updated.toString()));

        var request = ArgumentCaptor.forClass(ChangeEncounterStatusRequest.class);

        verify(encounters).changeStatus(eq(ID), request.capture(), eq(ACTOR));

        assertThat(request.getValue().expectedStatus()).isEqualTo(EncounterStatus.SCHEDULED);
        assertThat(request.getValue().status()).isEqualTo(EncounterStatus.ARRIVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"expectedStatus\":null,\"status\":\"arrived\"}",
            "{\"expectedStatus\":\"scheduled\",\"status\":null}",
            "{\"expectedStatus\":\"scheduled\",\"status\":\"bogus\"}",
            "{\"expectedStatus\":\"bogus\",\"status\":\"arrived\"}",
            "{"
    })
    void rejectsMalformedAndMissingStates(String body) throws Exception {
        mvc.perform(patch(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(encounters);
    }

    @Test
    void rejectsMalformedUuidAndOverlongReason() throws Exception {
        mvc.perform(patch("/api/v1/hospital/encounters/not-a-uuid/status")
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStatus\":\"scheduled\",\"status\":\"cancelled\","
                                + "\"reason\":\"" + "x".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(encounters);
    }

    @Test
    void requiresAdminForCanonicalAndTrailingSlashRoutes() throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        for (String path : new String[]{PATH, PATH + "/"}) {
            mvc.perform(patch(path)
                            .with(jwt().authorities(createAuthorityList("ROLE_USER")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(encounters);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ENCOUNTER_NOT_FOUND",
            "ENCOUNTER_STATUS_CONFLICT",
            "ENCOUNTER_TRANSITION_NOT_ALLOWED",
            "ENCOUNTER_REASON_REQUIRED",
            "ENCOUNTER_LOCK_CONFLICT",
            "ENCOUNTER_CHANGE_FAILED"
    })
    void mapsSafeBusinessErrors(String code) throws Exception {
        ErrorCode error = ErrorCode.valueOf(code);

        given(encounters.changeStatus(any(), any(), any()))
                .willThrow(new BusinessException(error));

        mvc.perform(patch(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                )
                .andExpect(status().is(error.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(error.getMessage()));
    }
}
