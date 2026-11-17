package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClinicalAuditLogRepository
        extends JpaRepository<@NonNull ClinicalAuditLog, @NonNull UUID> {
}
