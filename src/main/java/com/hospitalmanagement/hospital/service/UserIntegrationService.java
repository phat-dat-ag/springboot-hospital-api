package com.hospitalmanagement.hospital.service;

import com.hospitalmanagement.hospital.dto.response.UserIntegrationResponse;

import java.util.UUID;

public interface UserIntegrationService {

    UserIntegrationResponse getUser(UUID userId);
}
