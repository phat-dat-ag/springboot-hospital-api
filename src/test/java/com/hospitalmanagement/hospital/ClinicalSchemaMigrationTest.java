package com.hospitalmanagement.hospital;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in, server-free tests against a dedicated empty PostgreSQL database.
 */
@EnabledIfEnvironmentVariable(named = "CLINICAL_DATABASE_TESTS", matches = "true")
class ClinicalSchemaMigrationTest {
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "patients", "patient_contacts", "patient_allergies",
            "patient_medical_histories", "doctors", "specialties",
            "doctor_specialties", "medical_encounters", "vital_signs",
            "clinical_notes", "encounter_status_histories", "follow_up_plans",
            "diagnosis_codes", "encounter_diagnoses", "diagnosis_histories",
            "lab_test_categories", "lab_tests", "lab_orders", "lab_order_items",
            "lab_results", "medication_categories", "medications",
            "prescriptions", "prescription_items", "prescription_histories",
            "medical_attachments", "clinical_audit_logs",
            "medical_record_access_logs", "encounter_registration_keys"
    );

    private static Flyway flyway;

    @BeforeAll
    static void migrateEmptyDatabase() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                     """)) {
            result.next();
            assertThat(result.getInt(1))
                    .as("Use a dedicated EMPTY test database, never clinical_db")
                    .isZero();
        }

        flyway = Flyway.configure()
                .dataSource(
                        requiredEnvironment("CLINICAL_TEST_JDBC_URL"),
                        requiredEnvironment("CLINICAL_TEST_DB_USER"),
                        requiredEnvironment("CLINICAL_TEST_DB_PASSWORD")
                )
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
    }

    @Test
    void createsExactlyExpectedTablesWithPrimaryAndForeignKeys() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            Set<String> tables = strings(statement, """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                        AND table_name <> 'flyway_schema_history'
                    """);
            assertThat(tables).isEqualTo(EXPECTED_TABLES);

            Set<String> primaryKeys = strings(statement, """
                    SELECT table_name FROM information_schema.table_constraints
                    WHERE table_schema = 'public' AND constraint_type = 'PRIMARY KEY'
                        AND table_name <> 'flyway_schema_history'
                    """);
            assertThat(primaryKeys).isEqualTo(EXPECTED_TABLES);

            Set<String> foreignKeyOwners = strings(statement, """
                    SELECT table_name FROM information_schema.table_constraints
                    WHERE table_schema = 'public' AND constraint_type = 'FOREIGN KEY'
                    """);
            Set<String> expectedOwners = new HashSet<>(EXPECTED_TABLES);
            expectedOwners.removeAll(Set.of(
                    "patients", "doctors", "specialties", "diagnosis_codes",
                    "lab_test_categories", "medication_categories"
            ));
            assertThat(foreignKeyOwners).isEqualTo(expectedOwners);

            assertThat(strings(statement, """
                    SELECT conname FROM pg_constraint
                    WHERE contype = 'f' AND connamespace = 'public'::regnamespace
                        AND (confdeltype <> 'a' OR confupdtype <> 'a'
                            OR confrelid NOT IN (
                                SELECT oid FROM pg_class
                                WHERE relnamespace = 'public'::regnamespace))
                    """)).isEmpty();
        }
    }

    @Test
    void enforcesClinicalIntegrityAndTimestampPolicies() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             var stream = Objects.requireNonNull(
                     getClass().getResourceAsStream("/db/clinical_schema_checks.sql")
             )) {
            connection.setAutoCommit(false);
            try {
                statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void validatesChecksumsAndSecondMigrationMakesNoChanges() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void rejectsAnIdempotencyClaimWithoutItsEncounterAtCommit() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("""
                        INSERT INTO encounter_registration_keys (
                            actor_user_id, idempotency_key, request_digest, encounter_id
                        ) VALUES (
                            gen_random_uuid(), 'test-key', decode(repeat('ab', 32), 'hex'),
                            gen_random_uuid()
                        )
                        """);
                assertThatThrownBy(connection::commit)
                        .isInstanceOfSatisfying(SQLException.class, failure ->
                                assertThat(failure.getSQLState()).isEqualTo("23503"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CLINICAL_UPGRADE_JDBC_URL", matches = ".+")
    void upgradesAnExistingVersionTenSchema() {
        String url = requiredEnvironment("CLINICAL_UPGRADE_JDBC_URL");

        assertThat(url).matches("jdbc:postgresql://[^/]+/clinical_upgrade_test(?:\\?.*)?");

        var configuration = Flyway.configure()
                .dataSource(
                        url,
                        requiredEnvironment("CLINICAL_TEST_DB_USER"),
                        requiredEnvironment("CLINICAL_TEST_DB_PASSWORD")
                )
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .cleanDisabled(true)
                .baselineOnMigrate(false);
        assertThat(configuration.target("10").load().migrate().migrationsExecuted).isEqualTo(10);

        Flyway upgraded = configuration.target("11").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    }

    private static Connection connect() throws SQLException {
        String url = requiredEnvironment("CLINICAL_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/clinical_schema_test(?:\\?.*)?")) {
            throw new IllegalArgumentException(
                    "Test URL must target the dedicated clinical_schema_test database"
            );
        }
        return DriverManager.getConnection(
                url,
                requiredEnvironment("CLINICAL_TEST_DB_USER"),
                requiredEnvironment("CLINICAL_TEST_DB_PASSWORD")
        );
    }

    private static String requiredEnvironment(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " must be set");
    }

    private static Set<String> strings(Statement statement, String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
