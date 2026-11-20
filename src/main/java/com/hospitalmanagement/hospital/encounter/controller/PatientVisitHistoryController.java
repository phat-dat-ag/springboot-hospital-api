package com.hospitalmanagement.hospital.encounter.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.read.EncounterReadService;
import com.hospitalmanagement.hospital.encounter.read.EncounterReadService.PatientHistory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hospital/patients/{id}/encounters")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PatientVisitHistoryController extends BaseController {
    private final EncounterReadService encounters;

    @GetMapping
    @Operation(summary = "List the verified doctor's completed visits with this patient")
    @ApiResponse(responseCode = "404", description = "No patient chart in the caller's scope")
    public ResponseEntity<@NonNull APIResponse<PatientHistory>> visits(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        return success(
                encounters.visits(id, cursor, size, authentication),
                ResponseEntity.ok().cacheControl(CacheControl.noStore())
        );
    }
}
