package com.hospitalmanagement.hospital.encounter.dto.request;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeEncounterStatusRequest(
        @NotNull(message = "Expected status is required")
        @Schema(example = "scheduled")
        EncounterStatus expectedStatus,

        @NotNull(message = "Target status is required")
        @Schema(example = "arrived")
        EncounterStatus status,

        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        @Schema(description = "Required for cancellation; retained in clinical history")
        String reason
) {
    public ChangeEncounterStatusRequest {
        if (reason != null) {
            reason = reason.strip();
        }
    }
}

