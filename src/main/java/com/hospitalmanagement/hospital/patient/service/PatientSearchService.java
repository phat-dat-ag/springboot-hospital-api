package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientSearchResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface PatientSearchService {
    PatientSearchResponse searchPatients(
            @NotNull(message = "Patient search criteria are required")
            @Valid
            PatientSearchRequest request,

            String authenticatedSubject
    );
}
