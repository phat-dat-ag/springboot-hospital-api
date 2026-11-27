package com.hospitalmanagement.hospital.service.impl;

import com.hospitalmanagement.hospital.client.UserServiceClient;
import com.hospitalmanagement.hospital.dto.response.UserIntegrationResponse;
import com.hospitalmanagement.hospital.service.UserIntegrationService;
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