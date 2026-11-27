package com.hospitalmanagement.hospital.integration.user.service;

import com.hospitalmanagement.hospital.integration.user.dto.UserIntegrationResponse;

import java.util.UUID;

public interface UserIntegrationService {

    UserIntegrationResponse getUser(UUID userId);
}
