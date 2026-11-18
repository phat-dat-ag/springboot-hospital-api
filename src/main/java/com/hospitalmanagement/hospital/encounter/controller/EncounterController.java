package com.hospitalmanagement.hospital.encounter.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterStatusResponse;
import com.hospitalmanagement.hospital.encounter.service.EncounterStatusService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/encounters")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
public class EncounterController extends BaseController {
    private final EncounterStatusService encounters;

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "Change encounter status",
            description = "Requires ADMIN. Locks the encounter, checks expectedStatus and records "
                    + "history and audit atomically. Terminal encounters cannot be reopened."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status changed"),
            @ApiResponse(responseCode = "400", description = "Invalid UUID, status or reason"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing authentication"),
            @ApiResponse(responseCode = "403", description = "ADMIN authority required"),
            @ApiResponse(responseCode = "404", description = "Encounter not found"),
            @ApiResponse(responseCode = "409", description = "Status, workflow or lock conflict"),
            @ApiResponse(responseCode = "500", description = "Status change could not be recorded")
    })
    public ResponseEntity<@NonNull APIResponse<EncounterStatusResponse>> changeStatus(
            @PathVariable
            UUID id,

            @RequestBody
            @Valid
            ChangeEncounterStatusRequest request,

            JwtAuthenticationToken authentication
    ) {
        return success(encounters.changeStatus(
                id,
                request,
                authentication.getToken().getSubject()
        ));
    }
}

