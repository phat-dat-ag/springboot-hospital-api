package com.hospitalmanagement.hospital.patient.dto.response;

import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PatientDemographicsResponse(
        UUID id,
        String medicalRecordNumber,
        String givenName,
        String familyName,
        LocalDate dateOfBirth,
        SexAtBirth sexAtBirth,
        Instant updatedAt
) {
}
