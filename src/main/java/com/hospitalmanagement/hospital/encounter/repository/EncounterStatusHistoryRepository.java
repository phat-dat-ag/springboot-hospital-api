package com.hospitalmanagement.hospital.encounter.repository;

import com.hospitalmanagement.hospital.encounter.entity.EncounterStatusHistory;

import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EncounterStatusHistoryRepository
        extends JpaRepository<@NonNull EncounterStatusHistory, @NonNull UUID> {
}

