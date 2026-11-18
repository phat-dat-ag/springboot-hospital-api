package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MedicalRecordAccessLogRepository
        extends JpaRepository<@NonNull MedicalRecordAccessLog, @NonNull UUID> {
}
