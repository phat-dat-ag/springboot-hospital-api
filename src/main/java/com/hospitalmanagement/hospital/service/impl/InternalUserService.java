package com.hospitalmanagement.hospital.service.impl;

import com.hospitalmanagement.hospital.client.UserServiceClient;
import com.hospitalmanagement.hospital.dto.response.InternalUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InternalUserService {
    private final UserServiceClient userServiceClient;

    public InternalUserResponse getUser(UUID uuid) {
        return userServiceClient.getUser(uuid);
    }
}