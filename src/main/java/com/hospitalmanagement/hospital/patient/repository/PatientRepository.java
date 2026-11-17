package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.entity.Patient;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository
        extends JpaRepository<@NonNull Patient, @NonNull UUID> {
}
