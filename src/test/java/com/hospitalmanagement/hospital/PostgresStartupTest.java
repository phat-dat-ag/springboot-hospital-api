package com.hospitalmanagement.hospital;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Requires an existing, configured PostgreSQL instance; never starts Docker. */
@EnabledIfEnvironmentVariable(named = "HOSPITAL_DATABASE_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class PostgresStartupTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void startsAgainstHospitalDatabaseAndValidatesMigrations() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("hospital_db");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
