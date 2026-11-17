package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.entity.PatientContact;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientContactRepository
        extends JpaRepository<@NonNull PatientContact, @NonNull UUID> {
}
