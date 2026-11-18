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
}

