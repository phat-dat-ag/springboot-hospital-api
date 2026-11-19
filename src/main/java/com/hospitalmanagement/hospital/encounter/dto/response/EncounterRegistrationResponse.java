package com.hospitalmanagement.hospital.encounter.dto.response;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;

import java.time.Instant;
import java.util.UUID;

public record EncounterRegistrationResponse(
        UUID id,
        String encounterNumber,
        UUID patientId,
        UUID doctorId,
        EncounterStatus status,
        Instant scheduledAt,
        Instant createdAt
) {
}
