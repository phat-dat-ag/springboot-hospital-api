package com.hospitalmanagement.hospital.encounter.repository;

import com.hospitalmanagement.hospital.encounter.entity.MedicalEncounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface MedicalEncounterRepository extends JpaRepository<MedicalEncounter, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select encounter from MedicalEncounter encounter where encounter.id = :id")
    Optional<MedicalEncounter> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM clinical_notes
                WHERE encounter_id = :id AND note_type = 'assessment'
                  AND status = 'signed' AND signed_at IS NOT NULL
            )
            """, nativeQuery = true)
    boolean hasSignedAssessment(@Param("id") UUID id);

    @Query(value = """
            SELECT count(*) FROM encounter_diagnoses
            WHERE encounter_id = :id AND status = 'active' AND is_primary
            """, nativeQuery = true)
    long countActivePrimaryDiagnoses(@Param("id") UUID id);
}

