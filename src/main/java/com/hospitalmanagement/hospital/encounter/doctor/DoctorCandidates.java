package com.hospitalmanagement.hospital.encounter.doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DoctorCandidates {
    private static final String DISPLAY_NAME = "concat_ws(' ', d.given_name, nullif(d.family_name, ''))";

    private final JdbcClient jdbc;

    public List<Candidate> scan(String name, UUID after, LocalDate date, int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.external_user_id, %s AS display_name, s.name AS specialty
                        FROM doctors d
                        LEFT JOIN doctor_specialties ds ON ds.doctor_id = d.id
                            AND ds.is_primary AND ds.is_active
                        LEFT JOIN specialties s ON s.id = ds.specialty_id AND s.deleted_at IS NULL
                        WHERE d.deleted_at IS NULL AND d.external_user_id IS NOT NULL
                            AND (d.license_expires_on IS NULL OR d.license_expires_on >= :date)
                            AND (CAST(:after AS uuid) IS NULL OR d.id > :after)
                            AND strpos(lower(%s), lower(:name)) > 0
                        ORDER BY d.id
                        LIMIT :limit
                        """.formatted(DISPLAY_NAME, DISPLAY_NAME))
                .param("date", date)
                .param("after", after)
                .param("name", name)
                .param("limit", limit)
                .query((row, index) -> new Candidate(
                        row.getObject("id", UUID.class),
                        row.getObject("external_user_id", UUID.class),
                        row.getString("display_name"),
                        row.getString("specialty")
                ))
                .list();
    }

    public record Candidate(UUID id, UUID accountId, String displayName, String specialty) {
    }
}
