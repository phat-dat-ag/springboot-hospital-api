package com.hospitalmanagement.hospital.controller;

import com.hospitalmanagement.hospital.patient.service.PatientService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;

@SpringBootTest(
        properties = "spring.autoconfigure.exclude=" +
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
)
@AutoConfigureMockMvc
class OpenApiDocumentationTest {
    @MockitoBean
    private PatientService patients;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void patientRegistrationDocumentsCreatedConflictAndLowercaseEnums() throws Exception {
        String operation = "$.paths['/api/v1/hospital/patients'].post";
        String request = "$.components.schemas.RegisterPatientRequest.properties";
        String contact = "$.components.schemas.ContactRequest.properties";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".responses['201']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(request + ".sexAtBirth.enum")
                        .value(containsInAnyOrder("female", "male", "intersex", "unknown")))
                .andExpect(jsonPath(contact + ".contactType.enum")
                        .value(containsInAnyOrder("self", "emergency", "guardian")));
    }

    @Test
    void swaggerAndOpenApiArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Hospital Management API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/v1/hospital/technical/authentication'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/hospital/technical/users/{id}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/hospital/technical/jwks'].get.security[0].bearerAuth").isArray());
    }
}
