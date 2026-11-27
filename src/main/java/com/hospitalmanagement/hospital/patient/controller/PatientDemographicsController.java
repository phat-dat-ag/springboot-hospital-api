package com.hospitalmanagement.hospital.patient.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.patient.dto.response.PatientDemographicsResponse;
import com.hospitalmanagement.hospital.patient.service.PatientDemographicsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/patients")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
public class PatientDemographicsController extends BaseController {
    private final PatientDemographicsService patientDemographicsService;

    @GetMapping("/{id}")
    @Operation(
            summary = "Get active patient demographics",
            description = "Requires STAFF. "
                    + "Access is recorded before returning demographics."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient demographics"),
            @ApiResponse(responseCode = "400", description = "Invalid patient UUID"),
            @ApiResponse(responseCode = "401", description = "Invalid authentication"),
            @ApiResponse(responseCode = "403", description = "STAFF role required"),
            @ApiResponse(responseCode = "404", description = "Patient record unavailable"),
            @ApiResponse(responseCode = "500", description = "Unable to read patient record")
    })
    public ResponseEntity<@NonNull APIResponse<PatientDemographicsResponse>> getDemographics(
            @PathVariable
            UUID id,

            JwtAuthenticationToken authentication
    ) {
        PatientDemographicsResponse response = patientDemographicsService.getDemographics(
                id,
                authentication.getToken().getSubject()
        );

        return success(
                response,
                ResponseEntity.ok().cacheControl(CacheControl.noStore())
        );
    }
}
