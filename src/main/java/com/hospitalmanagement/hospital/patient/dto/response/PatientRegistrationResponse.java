package com.hospitalmanagement.hospital.patient.dto.response;

import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PatientRegistrationResponse(
        UUID id,
        String medicalRecordNumber,
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        SexAtBirth sexAtBirth,
        Instant createdAt,
        List<UUID> contactIds
) {
    public PatientRegistrationResponse {
        contactIds = List.copyOf(contactIds);
    }
}
