package com.hospitalmanagement.hospital.encounter.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record RegisterEncounterRequest(
        @NotNull UUID patientId,
        @NotNull UUID doctorId,
        @NotBlank @Size(max = 2000) String chiefComplaint,
        Instant scheduledAt
) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported encounter registration field");
    }
}
