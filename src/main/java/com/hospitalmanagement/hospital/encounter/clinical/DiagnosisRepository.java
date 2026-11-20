package com.hospitalmanagement.hospital.encounter.clinical;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DiagnosisRepository {
    private final JdbcClient jdbc;

    List<Code> codes(String query, long offset, int limit) {
        return jdbc.sql("""
                        SELECT id, coding_system, system_version, code, description
                        FROM diagnosis_codes
                        WHERE is_active AND (strpos(lower(code), lower(:query)) > 0
                            OR strpos(lower(description), lower(:query)) > 0)
                        ORDER BY coding_system, system_version, code, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("query", query).param("offset", offset).param("limit", limit)
                .query(DiagnosisRepository::code).list();
    }

    Optional<Code> activeCodeForShare(UUID id) {
        return jdbc.sql("""
                        SELECT id, coding_system, system_version, code, description
                        FROM diagnosis_codes WHERE id = :id AND is_active FOR SHARE
                        """)
                .param("id", id).query(DiagnosisRepository::code).optional();
    }

    Optional<Diagnosis> insert(
            UUID encounter, UUID doctor, Code code, boolean primary, String description
    ) {
        return jdbc.sql("""
                        INSERT INTO encounter_diagnoses (
                            encounter_id, diagnosis_code_id, diagnosing_doctor_id,
                            is_primary, clinical_description
                        ) VALUES (:encounter, :code, :doctor, :primary, :description)
                        ON CONFLICT DO NOTHING
                        RETURNING id AS diagnosis_id, diagnosing_doctor_id, is_primary,
                            clinical_description, status, diagnosed_at
                        """)
                .param("encounter", encounter).param("code", code.id()).param("doctor", doctor)
                .param("primary", primary).param("description", description)
                .query((row, index) -> diagnosis(row, code)).optional();
    }

    void recordHistory(Diagnosis diagnosis, UUID actor) {
        jdbc.sql("""
                        INSERT INTO diagnosis_histories (
                            encounter_diagnosis_id, new_code_id, new_status, is_primary,
                            new_description, actor_user_id, reason, occurred_at
                        ) VALUES (
                            :id, :code, :status, :primary, :description,
                            :actor, 'initial_recording', :diagnosed
                        )
                        """)
                .param("id", diagnosis.id()).param("code", diagnosis.code().id())
                .param("status", diagnosis.status()).param("primary", diagnosis.primary())
                .param("description", diagnosis.clinicalDescription()).param("actor", actor)
                .param("diagnosed", diagnosis.diagnosedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    List<Diagnosis> diagnoses(UUID encounter, long offset, int limit) {
        return jdbc.sql("""
                        SELECT d.id AS diagnosis_id, d.diagnosing_doctor_id, d.is_primary,
                            d.clinical_description, d.status, d.diagnosed_at,
                            c.id, c.coding_system, c.system_version, c.code, c.description
                        FROM encounter_diagnoses d JOIN diagnosis_codes c ON c.id = d.diagnosis_code_id
                        WHERE d.encounter_id = :encounter AND d.status = 'active'
                        ORDER BY d.is_primary DESC, d.diagnosed_at DESC, d.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("encounter", encounter).param("offset", offset).param("limit", limit)
                .query((row, index) -> diagnosis(row, code(row, index))).list();
    }

    private static Code code(ResultSet row, int index) throws SQLException {
        return new Code(row.getObject("id", UUID.class), row.getString("coding_system"),
                row.getString("system_version"), row.getString("code"),
                row.getString("description"));
    }

    private static Diagnosis diagnosis(ResultSet row, Code code) throws SQLException {
        return new Diagnosis(row.getObject("diagnosis_id", UUID.class), code,
                row.getObject("diagnosing_doctor_id", UUID.class), row.getBoolean("is_primary"),
                row.getString("clinical_description"), row.getString("status"),
                row.getObject("diagnosed_at", OffsetDateTime.class).toInstant());
    }

    public record Code(UUID id, String codingSystem, String systemVersion, String code,
                       String description) {
    }

    public record Diagnosis(UUID id, Code code, UUID diagnosingDoctorId, boolean primary,
                            String clinicalDescription, String status, Instant diagnosedAt) {
    }
}
