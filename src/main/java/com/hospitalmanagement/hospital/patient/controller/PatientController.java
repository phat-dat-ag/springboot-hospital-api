package com.hospitalmanagement.hospital.patient.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientRegistrationResponse;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSearchResponse;
import com.hospitalmanagement.hospital.patient.service.PatientSearchService;
import com.hospitalmanagement.hospital.patient.service.PatientService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/hospital/patients")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
public class PatientController extends BaseController {
    private final PatientService patientService;
    private final PatientSearchService patientSearchService;

    @GetMapping
    @Operation(
            summary = "Search active patients",
            description = "Requires STAFF and at least one filter. Returned records are access-logged."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient search results, possibly empty"),
            @ApiResponse(responseCode = "400", description = "Invalid filters, pagination or sort"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing authentication"),
            @ApiResponse(responseCode = "403", description = "STAFF authority required"),
            @ApiResponse(responseCode = "500", description = "Unable to record patient access")
    })
    public ResponseEntity<@NonNull APIResponse<PatientSearchResponse>> searchPatients(
            @ParameterObject
            @ModelAttribute
            @Valid
            PatientSearchRequest request,

            JwtAuthenticationToken authentication
    ) {
        return success(patientSearchService.searchPatients(
                request,
                authentication.getToken().getSubject()
        ));
    }

    @PostMapping
    @Operation(
            summary = "Register a patient and optional contacts",
            description = "Requires STAFF. Saves patient, contacts and audit metadata atomically."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Patient registered"),
            @ApiResponse(responseCode = "400", description = "Invalid registration or contacts"),
            @ApiResponse(responseCode = "401", description = "Invalid or missing authentication"),
            @ApiResponse(responseCode = "403", description = "STAFF authority required"),
            @ApiResponse(responseCode = "409", description = "Medical record number already exists")
    })
    public ResponseEntity<@NonNull APIResponse<PatientRegistrationResponse>> registerPatient(
            @RequestBody
            @Valid
            RegisterPatientRequest request,

            JwtAuthenticationToken authentication
    ) {
        PatientRegistrationResponse response = patientService.registerPatient(
                request,
                authentication.getToken().getSubject()
        );

        return created(
                "PATIENT_REGISTERED",
                "Patient registered successfully",
                response
        );
    }
}
