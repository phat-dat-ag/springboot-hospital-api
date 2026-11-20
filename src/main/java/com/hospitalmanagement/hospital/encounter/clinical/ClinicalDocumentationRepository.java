package com.hospitalmanagement.hospital.encounter.clinical;

import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Note;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.NoteSummary;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.VitalMeasurements;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Vitals;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ClinicalDocumentationRepository {
    private final JdbcClient jdbc;

    Vitals insertVitals(UUID encounter, UUID actor, Instant measured, VitalMeasurements values) {
        return jdbc.sql("""
                INSERT INTO vital_signs (
                    encounter_id, recorded_by_user_id, measured_at, temperature_c,
                    systolic_bp_mmhg, diastolic_bp_mmhg, heart_rate_bpm, respiratory_rate_per_min,
                    oxygen_saturation_pct, weight_kg, height_cm
                ) VALUES (
                    :encounter, :actor, :measured, :temp, :systolic, :diastolic, :heart,
                    :respiratory, :oxygen, :weight, :height
                ) RETURNING *
                """)
                .param("encounter", encounter).param("actor", actor)
                .param("measured", measured.atOffset(ZoneOffset.UTC))
                .param("temp", values.temperatureC()).param("systolic", values.systolicBpMmhg())
                .param("diastolic", values.diastolicBpMmhg()).param("heart", values.heartRateBpm())
                .param("respiratory", values.respiratoryRatePerMin())
                .param("oxygen", values.oxygenSaturationPct())
                .param("weight", values.weightKg()).param("height", values.heightCm())
                .query(ClinicalDocumentationRepository::vitals)
                .single();
    }

    List<Vitals> vitals(UUID encounter, long offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM vital_signs WHERE encounter_id = :encounter
                    AND status IN ('recorded', 'corrected')
                ORDER BY measured_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .param("encounter", encounter).param("limit", limit).param("offset", offset)
                .query(ClinicalDocumentationRepository::vitals)
                .list();
    }

    Note insertNote(UUID encounter, UUID doctor, String content) {
        return jdbc.sql("""
                INSERT INTO clinical_notes (encounter_id, author_doctor_id, note_type, content)
                VALUES (:encounter, :doctor, 'assessment', :content) RETURNING *
                """)
                .param("encounter", encounter).param("doctor", doctor).param("content", content)
                .query(ClinicalDocumentationRepository::note)
                .single();
    }

    List<NoteSummary> notes(UUID encounter, UUID doctor, long offset, int limit) {
        return jdbc.sql("""
                SELECT id, author_doctor_id, note_type, status, created_at, updated_at, signed_at
                FROM clinical_notes WHERE encounter_id = :encounter
                    AND (status = 'signed' OR (status = 'draft' AND author_doctor_id = :doctor))
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .param("encounter", encounter).param("doctor", doctor)
                .param("limit", limit).param("offset", offset)
                .query(ClinicalDocumentationRepository::summary)
                .list();
    }

    Optional<Note> note(UUID encounter, UUID id, UUID doctor, boolean lock) {
        String sql = """
                SELECT * FROM clinical_notes WHERE encounter_id = :encounter AND id = :id
                    AND (status = 'signed' OR (status = 'draft' AND author_doctor_id = :doctor))
                """;

        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .param("encounter", encounter).param("id", id).param("doctor", doctor)
                .query(ClinicalDocumentationRepository::note)
                .optional();
    }

    Note edit(UUID encounter, UUID id, String content) {
        return jdbc.sql("""
                UPDATE clinical_notes SET content = :content
                WHERE encounter_id = :encounter AND id = :id AND status = 'draft' RETURNING *
                """)
                .param("encounter", encounter).param("id", id).param("content", content)
                .query(ClinicalDocumentationRepository::note)
                .single();
    }

    Note sign(UUID encounter, UUID id) {
        return jdbc.sql("""
                UPDATE clinical_notes SET status = 'signed',
                    signed_at = greatest(clock_timestamp(), created_at)
                WHERE encounter_id = :encounter AND id = :id AND status = 'draft' RETURNING *
                """)
                .param("encounter", encounter).param("id", id)
                .query(ClinicalDocumentationRepository::note)
                .single();
    }

    private static Vitals vitals(ResultSet row, int index) throws SQLException {
        return new Vitals(row.getObject("id", UUID.class), time(row, "measured_at"),
                time(row, "created_at"), new VitalMeasurements(
                        row.getBigDecimal("temperature_c"), row.getBigDecimal("systolic_bp_mmhg"),
                        row.getBigDecimal("diastolic_bp_mmhg"), row.getBigDecimal("heart_rate_bpm"),
                        row.getBigDecimal("respiratory_rate_per_min"),
                        row.getBigDecimal("oxygen_saturation_pct"), row.getBigDecimal("weight_kg"),
                        row.getBigDecimal("height_cm")
                ));
    }

    private static NoteSummary summary(ResultSet row, int index) throws SQLException {
        return new NoteSummary(row.getObject("id", UUID.class),
                row.getObject("author_doctor_id", UUID.class), row.getString("note_type"),
                row.getString("status"), time(row, "created_at"), time(row, "updated_at"),
                time(row, "signed_at"));
    }

    private static Note note(ResultSet row, int index) throws SQLException {
        NoteSummary summary = summary(row, index);
        String content = row.getString("content");

        return new Note(summary.id(), summary.authorDoctorId(), summary.type(), summary.status(),
                content, summary.createdAt(), summary.updatedAt(), summary.signedAt(),
                revision(summary, content));
    }

    private static String revision(NoteSummary note, String content) {
        String value = note.id() + "\n" + note.updatedAt() + "\n" + note.status() + "\n" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Instant time(ResultSet row, String field) throws SQLException {
        OffsetDateTime time = row.getObject(field, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }
}
