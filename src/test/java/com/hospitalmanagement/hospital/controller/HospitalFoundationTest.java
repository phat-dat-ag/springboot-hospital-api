package com.hospitalmanagement.hospital.controller;

import com.hospitalmanagement.hospital.encounter.doctor.DoctorCandidates;
import com.hospitalmanagement.hospital.encounter.registration.EncounterRegistrationService;
import com.hospitalmanagement.hospital.encounter.registration.EncounterRegistrationStore;
import com.hospitalmanagement.hospital.encounter.service.EncounterStatusService;
import com.hospitalmanagement.hospital.patient.service.PatientDemographicsService;
import com.hospitalmanagement.hospital.patient.service.PatientSearchService;
import com.hospitalmanagement.hospital.patient.service.PatientService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "management.endpoint.health.probes.enabled=true"
})
@MockitoBean(types = {
        DoctorCandidates.class,
        EncounterRegistrationService.class,
        EncounterRegistrationStore.class
})
@AutoConfigureMockMvc
class HospitalFoundationTest {
    @MockitoBean
    private PatientSearchService patientSearchService;

    @MockitoBean
    private PatientDemographicsService demographics;

    @MockitoBean
    private EncounterStatusService encounterStatusService;

    @MockitoBean
    private PatientService patients;

    @Autowired
    private MockMvc mvc;

    @Test
    void readinessIsPublicWithoutExternalDatabaseInThisTest() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void technicalJwksEndpointRequiresAUserRole() throws Exception {
        String path = "/api/v1/hospital/technical/jwks";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(createAuthorityList("ROLE_OTHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void obsoleteRoutesRemainAbsent() throws Exception {
        for (String path : new String[]{
                "/api/v1/learning/me",
                "/api/v1/integration/jwks"
        }) {
            mvc.perform(get(path).with(jwt().authorities(createAuthorityList("ROLE_USER"))))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void patientSearchRequiresAdmin() throws Exception {
        mvc.perform(get("/api/v1/hospital/patients")
                        .with(jwt().authorities(createAuthorityList("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }
}
