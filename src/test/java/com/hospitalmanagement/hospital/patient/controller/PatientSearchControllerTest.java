package com.hospitalmanagement.hospital.patient.controller;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.common.exception.UnreadableRequestDetails;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSearchResponse;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSummaryResponse;
import com.hospitalmanagement.hospital.patient.service.PatientSearchService;
import com.hospitalmanagement.hospital.patient.service.PatientService;
import com.hospitalmanagement.hospital.security.config.UserSecurityConfig;
import com.hospitalmanagement.hospital.security.jwt.user.JwtRoleConverter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientController.class)
@Import({UserSecurityConfig.class, JwtRoleConverter.class, UnreadableRequestDetails.class})
class PatientSearchControllerTest {
    private static final String PATH = "/api/v1/hospital/patients";
    private static final UUID ACTOR = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PatientSearchService searches;

    @MockitoBean
    private PatientService registrations;

    @MockitoBean
    private JwtDecoder decoder;

    @Test
    void returnsStablePageDtoAndBindsDefaults() throws Exception {
        UUID patientId = UUID.randomUUID();

        given(searches.searchPatients(any(), eq(ACTOR.toString())))
                .willReturn(new PatientSearchResponse(
                        List.of(new PatientSummaryResponse(
                                patientId,
                                "LEARN-0001",
                                "Example",
                                null,
                                LocalDate.of(1995, 6, 15)
                        )),
                        0,
                        20,
                        1,
                        1
                ));

        mvc.perform(get(PATH)
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))
                                .authorities(createAuthorityList("ROLE_ADMIN")))
                        .param("medicalRecordNumber", " LEARN-0001 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(patientId.toString()))
                .andExpect(jsonPath("$.data.items[0].dateOfBirth").value("1995-06-15"))
                .andExpect(jsonPath("$.data.items[0].contacts").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].externalUserId").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.meta.timestamp").exists());

        var criteria = ArgumentCaptor.forClass(PatientSearchRequest.class);

        verify(searches).searchPatients(criteria.capture(), eq(ACTOR.toString()));

        assertThat(criteria.getValue()).isEqualTo(new PatientSearchRequest(
                "LEARN-0001", null, null, 0, 20, "createdAt,desc"
        ));
    }

    @Test
    void returnsEmptyPageAndBindsCombinedFiltersAndSort() throws Exception {
        given(searches.searchPatients(any(), any()))
                .willReturn(new PatientSearchResponse(List.of(), 2, 5, 0, 0));

        mvc.perform(get(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .param("medicalRecordNumber", "EXACT")
                        .param("name", " example ")
                        .param("dateOfBirth", "1995-06-15")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "medicalRecordNumber,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        var criteria = ArgumentCaptor.forClass(PatientSearchRequest.class);

        verify(searches).searchPatients(criteria.capture(), any());

        assertThat(criteria.getValue()).isEqualTo(new PatientSearchRequest(
                "EXACT",
                "example",
                LocalDate.of(1995, 6, 15),
                2,
                5,
                "medicalRecordNumber,asc"
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidParametersBeforeCallingService(
            String parameter,
            String value
    ) throws Exception {
        var request = get(PATH)
                .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")));

        if (!"medicalRecordNumber".equals(parameter)) {
            request.param("medicalRecordNumber", "EXACT");
        }

        mvc.perform(request.param(parameter, value))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(searches);
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of("name", "a"),
                Arguments.of("name", " "),
                Arguments.of("name", "\u2003\u2003"),
                Arguments.of("medicalRecordNumber", " "),
                Arguments.of("dateOfBirth", "not-a-date"),
                Arguments.of("dateOfBirth", "2026-02-30"),
                Arguments.of("page", "-1"),
                Arguments.of("page", "not-a-number"),
                Arguments.of("page", "2147483648"),
                Arguments.of("size", "0"),
                Arguments.of("size", "-1"),
                Arguments.of("size", "101"),
                Arguments.of("sort", "givenName,asc"),
                Arguments.of("sort", "createdAt,invalid"),
                Arguments.of("sort", "createdAt")
        );
    }

    @Test
    void requiresAdminWithoutChangingOtherEndpointRules() throws Exception {
        mvc.perform(get(PATH).param("name", "example"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH)
                .with(jwt().authorities(createAuthorityList("ROLE_USER")))
                .param("name", "example")
        ).andExpect(status().isForbidden());

        verifyNoInteractions(searches);
    }

    @Test
    void mapsMissingFiltersToSafeError() throws Exception {
        given(searches.searchPatients(any(), any()))
                .willThrow(new BusinessException(ErrorCode.PATIENT_SEARCH_FILTER_REQUIRED));

        mvc.perform(get(PATH).with(jwt().authorities(createAuthorityList("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PATIENT_SEARCH_FILTER_REQUIRED"));
    }

    @Test
    void mapsAccessLogFailureToSafeServerError() throws Exception {
        given(searches.searchPatients(any(), any()))
                .willThrow(new BusinessException(ErrorCode.PATIENT_ACCESS_LOG_FAILED));

        mvc.perform(get(PATH)
                        .with(jwt().authorities(createAuthorityList("ROLE_ADMIN")))
                        .param("name", "example")
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESS_LOG_FAILED"));
    }
}
