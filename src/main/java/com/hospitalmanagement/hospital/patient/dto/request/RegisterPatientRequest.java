package com.hospitalmanagement.hospital.patient.dto.request;

import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

@Schema(
        description = "Patient registration; no User Service account is required",
        example = """
                {
                  "medicalRecordNumber": "LEARN-0001",
                  "givenName": "An",
                  "familyName": "Example",
                  "dateOfBirth": "1995-06-15",
                  "sexAtBirth": "female",
                  "contacts": [
                    {"contactType": "self", "phone": "+66000000000", "isPrimary": true}
                  ]
                }
                """
)
public record RegisterPatientRequest(
        @NotBlank(message = "Medical record number is required")
        @Size(
                max = 40,
                message = "Medical record number must not exceed 40 characters"
        )
        String medicalRecordNumber,

        @NotBlank(message = "Given name is required")
        @Size(
                max = 100,
                message = "Given name must not exceed 100 characters"
        )
        String givenName,

        @Size(
                max = 100,
                message = "Family name must not exceed 100 characters"
        )
        String familyName,

        @PastOrPresent(message = "Date of birth must not be in the future")
        LocalDate dateOfBirth,

        SexAtBirth sexAtBirth,

        @Size(max = 5, message = "At most 5 contacts are allowed")
        List<@NotNull(message = "Contact must not be null") @Valid ContactRequest> contacts
) {
    public RegisterPatientRequest {
        if (medicalRecordNumber != null) {
            medicalRecordNumber = medicalRecordNumber.trim();
        }

        // Preserve null entries so Bean Validation can report their field paths.
        contacts = contacts == null
                ? List.of()
                : List.copyOf(contacts);
    }
}
