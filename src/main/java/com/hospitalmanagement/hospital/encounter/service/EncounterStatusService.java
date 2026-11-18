package com.hospitalmanagement.hospital.encounter.service;

import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterStatusResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public interface EncounterStatusService {
    EncounterStatusResponse changeStatus(
            @NotNull(message = "Encounter ID is required")
            UUID id,

            @NotNull(message = "Status change is required")
            @Valid
            ChangeEncounterStatusRequest request,

            String authenticatedSubject
    );
}
