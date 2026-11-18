package com.hospitalmanagement.hospital.patient.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record PatientSummaryResponse(
        UUID id,
        String medicalRecordNumber,
        String givenName,
        String familyName,
        LocalDate dateOfBirth
) {
}
