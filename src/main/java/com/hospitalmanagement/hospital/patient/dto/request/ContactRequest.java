package com.hospitalmanagement.hospital.patient.dto.request;

import com.hospitalmanagement.hospital.patient.enums.ContactType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cross-field contact rules are validated by the registration service.
 */
public record ContactRequest(
        @NotNull(message = "Contact type is required")
        ContactType contactType,

        @Size(
                max = 200,
                message = "Contact name must not exceed 200 characters"
        )
        String contactName,

        @Size(
                max = 100,
                message = "Relationship must not exceed 100 characters"
        )
        String relationship,

        @Size(
                max = 40,
                message = "Phone must not exceed 40 characters"
        )
        String phone,

        @Email(message = "Email must be a valid email address")
        @Size(
                max = 254,
                message = "Email must not exceed 254 characters"
        )
        String email,

        String addressText,

        Boolean isPrimary
) {
    public ContactRequest {
        if (isPrimary == null) {
            isPrimary = false;
        }
    }
}
