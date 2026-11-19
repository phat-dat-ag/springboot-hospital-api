package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.patient.dto.response.PatientDemographicsResponse;

import java.util.UUID;

public interface PatientDemographicsService {
    PatientDemographicsResponse getDemographics(UUID patientId, String subject);
}
