package com.hospitalmanagement.hospital.encounter.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.read.EncounterReadService;
import com.hospitalmanagement.hospital.encounter.read.EncounterView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/encounters")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
public class EncounterReadController extends BaseController {
    private final EncounterReadService encounters;

    @GetMapping
    @Operation(summary = "Read the clinic reception queue as STAFF")
    @ApiResponse(responseCode = "200", description = "Reception queue")
    @ApiResponse(responseCode = "400", description = "Invalid filter or page")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer")
    @ApiResponse(responseCode = "403", description = "STAFF authority required")
    @ApiResponse(responseCode = "500", description = "Unable to record encounter access")
    public ResponseEntity<@NonNull APIResponse<EncounterReadService.Page>> reception(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            @RequestParam(required = false)
            String status,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            JwtAuthenticationToken authentication
    ) {
        return success(encounters.queue(date, status, page, size, false, authentication), noStore());
    }

    @GetMapping("/assigned")
    @Operation(summary = "Read the authenticated DOCTOR's assigned queue")
    @ApiResponse(responseCode = "200", description = "Assigned queue")
    @ApiResponse(responseCode = "400", description = "Invalid filter or page")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer")
    @ApiResponse(responseCode = "403", description = "Active doctor identity required")
    @ApiResponse(responseCode = "500", description = "Unable to record encounter access")
    @ApiResponse(responseCode = "502", description = "Invalid account verification response")
    @ApiResponse(responseCode = "503", description = "Account verification unavailable")
    public ResponseEntity<@NonNull APIResponse<EncounterReadService.Page>> assigned(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            @RequestParam(required = false)
            String status,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            JwtAuthenticationToken authentication
    ) {
        return success(encounters.queue(date, status, page, size, true, authentication), noStore());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read operational details as STAFF or the assigned DOCTOR")
    @ApiResponse(responseCode = "200", description = "Operational encounter details")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer")
    @ApiResponse(responseCode = "403", description = "Active authorized identity required")
    @ApiResponse(responseCode = "404", description = "Missing or out-of-scope encounter")
    @ApiResponse(responseCode = "500", description = "Unable to record encounter access")
    @ApiResponse(responseCode = "502", description = "Invalid account verification response")
    @ApiResponse(responseCode = "503", description = "Account verification unavailable")
    public ResponseEntity<@NonNull APIResponse<EncounterView>> details(
            @PathVariable UUID id,
            JwtAuthenticationToken authentication
    ) {
        return success(encounters.details(id, authentication), noStore());
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore());
    }
}
