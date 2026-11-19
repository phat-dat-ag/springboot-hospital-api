package com.hospitalmanagement.hospital.encounter.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorSelectionService;
import com.hospitalmanagement.hospital.encounter.dto.request.RegisterEncounterRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterRegistrationResponse;
import com.hospitalmanagement.hospital.encounter.registration.EncounterRegistrationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
@Validated
public class OutpatientRegistrationController extends BaseController {
    private final DoctorSelectionService doctors;
    private final EncounterRegistrationService registrations;

    @GetMapping("/doctors")
    @Operation(summary = "Search verified eligible outpatient doctors as STAFF")
    @ApiResponse(responseCode = "200", description = "Verified results and optional next cursor")
    @ApiResponse(responseCode = "400", description = "Invalid search parameters")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "STAFF authority required")
    @ApiResponse(responseCode = "502", description = "Invalid eligibility response")
    @ApiResponse(responseCode = "503", description = "Eligibility dependency unavailable")
    public ResponseEntity<@NonNull APIResponse<DoctorSelectionService.Page>> searchDoctors(
            @RequestParam(defaultValue = "") @Size(max = 100) String name,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size
    ) {
        return success(
                doctors.search(name, after, size),
                ResponseEntity.ok().cacheControl(CacheControl.noStore())
        );
    }

    @PostMapping("/encounters")
    @Operation(summary = "Register an outpatient encounter as STAFF")
    @ApiResponse(responseCode = "201", description = "New encounter registered")
    @ApiResponse(responseCode = "200", description = "Previously committed registration replayed")
    @ApiResponse(responseCode = "400", description = "Invalid input, schedule or idempotency key")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "STAFF authority required")
    @ApiResponse(responseCode = "404", description = "Patient or doctor unavailable")
    @ApiResponse(responseCode = "409", description = "Registration conflicts with current data")
    @ApiResponse(responseCode = "502", description = "Invalid eligibility response")
    @ApiResponse(responseCode = "503", description = "Eligibility dependency unavailable")
    public ResponseEntity<@NonNull APIResponse<EncounterRegistrationResponse>> registerEncounter(
            @Parameter(required = true, description = "Client key, 1–128 ASCII letters or digits, . _ ~ -")
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RegisterEncounterRequest request,
            JwtAuthenticationToken authentication
    ) {
        var result = registrations.register(authentication.getToken().getSubject(), key, request);
        var status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;

        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(APIResponse.success(
                        result.replay() ? "ENCOUNTER_REPLAYED" : "ENCOUNTER_REGISTERED",
                        result.replay() ? "Encounter registration replayed" : "Encounter registered",
                        result.encounter()
                ));
    }
}
