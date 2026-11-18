package com.hospitalmanagement.hospital.encounter.dto.response;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;

import java.time.Instant;
import java.util.UUID;

public record EncounterStatusResponse(
        UUID id,
        UUID patientId,
        EncounterStatus status,
        Instant startedAt,
        Instant endedAt,
        Instant updatedAt
) {
}

