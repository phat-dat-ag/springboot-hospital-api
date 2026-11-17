package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientRegistrationResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface PatientService {
    PatientRegistrationResponse registerPatient(
            @NotNull(message = "Registration request is required")
            @Valid
            RegisterPatientRequest request,

            String authenticatedSubject
    );
}
