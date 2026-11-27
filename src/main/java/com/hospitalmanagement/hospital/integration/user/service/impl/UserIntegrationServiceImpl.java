package com.hospitalmanagement.hospital.integration.user.service.impl;

import com.hospitalmanagement.hospital.integration.user.client.UserServiceClient;
import com.hospitalmanagement.hospital.integration.user.dto.UserIntegrationResponse;
import com.hospitalmanagement.hospital.integration.user.service.UserIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserIntegrationServiceImpl implements UserIntegrationService {
    private final UserServiceClient userServiceClient;

    public UserIntegrationResponse getUser(UUID uuid) {
        return userServiceClient.getUser(uuid);
    }
}