package com.hospitalmanagement.hospital.encounter.clinical;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Page;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.RecordDiagnosis;
import com.hospitalmanagement.hospital.encounter.clinical.DiagnosisRepository.Code;
import com.hospitalmanagement.hospital.encounter.clinical.DiagnosisRepository.Diagnosis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "400", description = "Invalid request")
@ApiResponse(responseCode = "401", description = "Missing or invalid bearer")
@ApiResponse(responseCode = "403", description = "Current doctor identity required")
@ApiResponse(responseCode = "502", description = "Invalid account verification response")
@ApiResponse(responseCode = "503", description = "Account verification unavailable")
@RequiredArgsConstructor
public class DiagnosisController extends BaseController {
    private final ClinicalDocumentationService documentation;

    @GetMapping("/diagnosis-codes")
    @Operation(summary = "Search active diagnosis codes")
    public ResponseEntity<@NonNull APIResponse<Page<Code>>> codes(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.diagnosisCodes(q, page, size, authentication),
                response(HttpStatus.OK)
        );
    }

    @GetMapping("/encounters/{id}/diagnoses")
    @Operation(summary = "Read active diagnoses for an assigned encounter")
    @ApiResponse(responseCode = "404", description = "Encounter missing or outside assignment")
    @ApiResponse(responseCode = "500", description = "Required audit could not be recorded")
    public ResponseEntity<@NonNull APIResponse<Page<Diagnosis>>> diagnoses(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.diagnoses(id, page, size, authentication),
                response(HttpStatus.OK)
        );
    }

    @PostMapping("/encounters/{id}/diagnoses")
    @Operation(summary = "Record a diagnosis during an assigned examination")
    @ApiResponse(responseCode = "201", description = "Diagnosis recorded")
    @ApiResponse(responseCode = "404", description = "Encounter or active diagnosis code not found")
    @ApiResponse(responseCode = "409", description = "Duplicate code, primary or encounter conflict")
    @ApiResponse(responseCode = "500", description = "Required write could not be recorded")
    public ResponseEntity<@NonNull APIResponse<Diagnosis>> record(
            @PathVariable UUID id,
            @Valid @RequestBody RecordDiagnosis request,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.recordDiagnosis(id, request, authentication),
                response(HttpStatus.CREATED)
        );
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }
}
