package com.hospitalmanagement.hospital.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@AutoConfigureMockMvc
class HospitalFoundationTest {
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
    void obsoleteAndUnimplementedBusinessRoutesAreAbsent() throws Exception {
        for (String path : new String[]{
                "/api/v1/learning/me",
                "/api/v1/integration/jwks",
                "/api/v1/hospital/patients"
        }) {
            mvc.perform(get(path).with(jwt().authorities(createAuthorityList("ROLE_USER"))))
                    .andExpect(status().isNotFound());
        }
    }
}
