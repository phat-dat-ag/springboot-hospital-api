package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.dto.response.PatientDemographicsResponse;
import com.hospitalmanagement.hospital.patient.entity.Patient;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface PatientRepository
        extends JpaRepository<@NonNull Patient, @NonNull UUID>,
        JpaSpecificationExecutor<@NonNull Patient> {
    Optional<PatientDemographicsResponse> findDemographicsByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select patient from Patient patient where patient.id = :id")
    Optional<Patient> findByIdForUpdate(@Param("id") UUID id);
}
