package com.hospitalmanagement.hospital.encounter.clinical;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.CreateNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.EditNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Note;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.NoteSummary;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Page;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.RecordVitals;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.SignNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Vitals;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/encounters/{id}")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponse(responseCode = "400", description = "Invalid request")
@ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
@ApiResponse(responseCode = "403", description = "Current doctor identity required")
@ApiResponse(responseCode = "404", description = "Missing or out-of-scope resource")
@ApiResponse(responseCode = "409", description = "Encounter, lock or draft revision conflict")
@ApiResponse(responseCode = "500", description = "Required persistence failed")
@ApiResponse(responseCode = "502", description = "Invalid account verification response")
@ApiResponse(responseCode = "503", description = "Account verification unavailable")
@RequiredArgsConstructor
public class ClinicalDocumentationController extends BaseController {
    private final ClinicalDocumentationService documentation;

    @PostMapping("/vitals")
    @Operation(summary = "Record vital measurements during examination")
    @ApiResponse(responseCode = "201", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Vitals>> recordVitals(
            @PathVariable UUID id,
            @Valid @RequestBody RecordVitals request,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.recordVitals(id, request, authentication),
                response(HttpStatus.CREATED)
        );
    }

    @GetMapping("/vitals")
    @Operation(summary = "List vital measurements")
    @ApiResponse(responseCode = "200", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Page<Vitals>>> vitals(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.vitals(id, page, size, authentication),
                response(HttpStatus.OK)
        );
    }

    @PostMapping("/notes")
    @Operation(summary = "Create a draft assessment")
    @ApiResponse(responseCode = "201", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Note>> create(
            @PathVariable UUID id,
            @Valid @RequestBody CreateNote request,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.create(id, request, authentication),
                response(HttpStatus.CREATED)
        );
    }

    @GetMapping("/notes")
    @Operation(summary = "List visible note summaries")
    @ApiResponse(responseCode = "200", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Page<NoteSummary>>> notes(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.notes(id, page, size, authentication),
                response(HttpStatus.OK)
        );
    }

    @GetMapping("/notes/{noteId}")
    @Operation(summary = "Read a clinical note")
    @ApiResponse(responseCode = "200", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Note>> note(
            @PathVariable UUID id,
            @PathVariable UUID noteId,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.note(id, noteId, authentication),
                response(HttpStatus.OK)
        );
    }

    @PatchMapping("/notes/{noteId}")
    @Operation(summary = "Update the reviewed draft revision")
    @ApiResponse(responseCode = "200", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Note>> edit(
            @PathVariable UUID id,
            @PathVariable UUID noteId,
            @Valid @RequestBody EditNote request,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.edit(id, noteId, request, authentication),
                response(HttpStatus.OK)
        );
    }

    @PostMapping("/notes/{noteId}/signature")
    @Operation(summary = "Sign the reviewed draft revision")
    @ApiResponse(responseCode = "200", description = "Clinical request succeeded")
    public ResponseEntity<@NonNull APIResponse<Note>> sign(
            @PathVariable UUID id,
            @PathVariable UUID noteId,
            @Valid @RequestBody SignNote request,
            JwtAuthenticationToken authentication
    ) {
        return success(
                documentation.sign(id, noteId, request, authentication),
                response(HttpStatus.OK)
        );
    }

    @PutMapping("/conclusion")
    @Operation(summary = "Set the assigned encounter's independent clinical conclusion")
    @ApiResponse(responseCode = "200", description = "Conclusion stored")
    public ResponseEntity<@NonNull APIResponse<ClinicalDtos.Conclusion>> conclusion(
            @PathVariable UUID id,
            @Valid @RequestBody ClinicalDtos.UpdateConclusion request,
            JwtAuthenticationToken authentication
    ) {
        return success(documentation.updateConclusion(id, request, authentication),
                response(HttpStatus.OK));
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }
}
