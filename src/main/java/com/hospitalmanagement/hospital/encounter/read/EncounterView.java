package com.hospitalmanagement.hospital.encounter.read;

import java.time.Instant;
import java.util.UUID;

public record EncounterView(
        UUID id,
        String encounterNumber,
        UUID patientId,
        String patientName,
        UUID doctorId,
        String doctorName,
        String status,
        Instant scheduledAt,
        Instant arrivedAt,
        Instant startedAt,
        Instant endedAt,
        Instant updatedAt,
        String cancellationReason
) {
}
