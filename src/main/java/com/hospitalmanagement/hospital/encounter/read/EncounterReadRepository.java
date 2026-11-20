package com.hospitalmanagement.hospital.encounter.read;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class EncounterReadRepository {
    private static final String SELECT = """
            SELECT e.id, e.encounter_number, e.patient_id, e.doctor_id, e.status, e.created_at,
                   concat_ws(' ', p.given_name, nullif(p.family_name, '')) AS patient_name,
                   CASE WHEN d.id IS NOT NULL
                        THEN concat_ws(' ', d.given_name, nullif(d.family_name, ''))
                   END AS doctor_name,
                   e.scheduled_at, e.started_at, e.ended_at, e.updated_at,
                   (SELECT h.occurred_at FROM encounter_status_histories h
                    WHERE h.encounter_id = e.id AND h.to_status = 'arrived'
                    ORDER BY h.occurred_at LIMIT 1) AS arrived_at,
                   CASE WHEN :showReason AND e.status = 'cancelled'
                        THEN e.cancellation_reason END AS cancellation_reason,
                   CASE WHEN :showConclusion THEN e.clinical_conclusion END AS clinical_conclusion
            FROM medical_encounters e
            JOIN patients p ON p.id = e.patient_id AND p.deleted_at IS NULL
            LEFT JOIN doctors d ON d.id = e.doctor_id
            WHERE true
            """;
    private static final String DOCTOR_SCOPE = """
            AND e.doctor_id = :doctor AND d.external_user_id = :actor
            AND d.deleted_at IS NULL
            """;

    private final JdbcClient jdbc;

    public List<EncounterView> queue(
            OffsetDateTime start,
            OffsetDateTime end,
            List<String> statuses,
            long offset,
            int limit,
            UUID doctor,
            UUID actor
    ) {
        return scoped("""
                AND COALESCE(e.scheduled_at, e.created_at) >= :start
                AND COALESCE(e.scheduled_at, e.created_at) < :end
                AND e.status IN (:statuses)
                ORDER BY COALESCE(e.scheduled_at, e.created_at), e.id
                LIMIT :limit OFFSET :offset
                """, doctor, actor, false)
                .param("start", start)
                .param("end", end)
                .param("statuses", statuses)
                .param("limit", limit)
                .param("offset", offset)
                .query(EncounterReadRepository::map)
                .list();
    }

    public Optional<EncounterView> find(UUID id, UUID doctor, UUID actor) {
        return scoped("AND e.id = :id", doctor, actor, doctor == null)
                .param("id", id)
                .query(EncounterReadRepository::map)
                .optional();
    }

    public Optional<EncounterView> findDetails(UUID id, UUID doctor, UUID actor) {
        return scoped("AND e.id = :id", doctor, actor, doctor == null, doctor != null)
                .param("id", id)
                .query(EncounterReadRepository::map)
                .optional();
    }

    Optional<PatientHeader> patientWithCompletedVisit(UUID patient, UUID doctor, UUID actor) {
        return jdbc.sql("""
                        SELECT p.id, p.given_name, p.family_name
                        FROM patients p
                        WHERE p.id = :patient AND p.deleted_at IS NULL AND EXISTS (
                            SELECT 1 FROM medical_encounters e JOIN doctors d ON d.id = e.doctor_id
                            WHERE e.patient_id = p.id AND e.status = 'completed'
                              AND e.doctor_id = :doctor AND d.external_user_id = :actor
                              AND d.deleted_at IS NULL
                        )
                        """)
                .param("patient", patient).param("doctor", doctor).param("actor", actor)
                .query((row, index) -> new PatientHeader(
                        row.getObject("id", UUID.class), row.getString("given_name"),
                        row.getString("family_name")))
                .optional();
    }

    List<Visit> visits(UUID patient, UUID doctor, UUID actor, HistoryCursor cursor, int limit) {
        String boundary = cursor == null ? "" : "AND (e.created_at, e.id) < (:time, :cursorId)";
        var query = scoped("AND e.patient_id = :patient AND e.status = 'completed'\n"
                        + boundary + "\nORDER BY e.created_at DESC, e.id DESC LIMIT :limit",
                doctor, actor, false)
                .param("patient", patient).param("limit", limit);
        if (cursor != null) {
            query.param("time", cursor.utc()).param("cursorId", cursor.id());
        }

        return query.query((row, index) -> new Visit(map(row, index), instant(row, "created_at")))
                .list();
    }

    List<StatusEvent> statusHistory(UUID encounter, HistoryCursor cursor, int limit) {
        String boundary = cursor == null ? "" : "AND (occurred_at, id) > (:time, :cursorId)";
        var query = jdbc.sql("""
                        SELECT id, from_status, to_status, occurred_at
                        FROM encounter_status_histories WHERE encounter_id = :encounter
                        """ + boundary + "\nORDER BY occurred_at, id LIMIT :limit")
                .param("encounter", encounter).param("limit", limit);
        if (cursor != null) {
            query.param("time", cursor.utc()).param("cursorId", cursor.id());
        }

        return query.query((row, index) -> new StatusEvent(row.getObject("id", UUID.class),
                row.getString("from_status"), row.getString("to_status"),
                instant(row, "occurred_at"))).list();
    }

    public record PatientHeader(UUID id, String givenName, String familyName) {
    }

    public record Visit(EncounterView encounter, Instant createdAt) {
    }

    public record StatusEvent(UUID id, String fromStatus, String toStatus, Instant occurredAt) {
    }

    private JdbcClient.StatementSpec scoped(
            String tail,
            UUID doctor,
            UUID actor,
            boolean showReason
    ) {
        return scoped(tail, doctor, actor, showReason, false);
    }

    private JdbcClient.StatementSpec scoped(
            String tail,
            UUID doctor,
            UUID actor,
            boolean showReason,
            boolean showConclusion
    ) {
        if (doctor == null) {
            return jdbc.sql(SELECT + tail)
                    .param("showReason", showReason)
                    .param("showConclusion", false);
        }

        return jdbc.sql(SELECT + DOCTOR_SCOPE + tail)
                .param("showReason", false)
                .param("showConclusion", showConclusion)
                .param("doctor", doctor)
                .param("actor", actor);
    }

    private static EncounterView map(ResultSet row, int index) throws SQLException {
        return new EncounterView(
                row.getObject("id", UUID.class),
                row.getString("encounter_number"),
                row.getObject("patient_id", UUID.class),
                row.getString("patient_name"),
                row.getObject("doctor_id", UUID.class),
                row.getString("doctor_name"),
                row.getString("status"),
                instant(row, "scheduled_at"),
                instant(row, "arrived_at"),
                instant(row, "started_at"),
                instant(row, "ended_at"),
                instant(row, "updated_at"),
                row.getString("cancellation_reason"),
                row.getString("clinical_conclusion")
        );
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        OffsetDateTime value = row.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
