package com.hospitalmanagement.hospital.service.impl;

import com.hospitalmanagement.hospital.client.JwksServiceClient;
import com.hospitalmanagement.hospital.dto.response.PublicKeysResponse;
import com.hospitalmanagement.hospital.service.JwksIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwksIntegrationServiceImpl implements JwksIntegrationService {

    private final JwksServiceClient jwksServiceClient;

    @Override
    public PublicKeysResponse getPublicKeys() {
        return jwksServiceClient.getPublicKeys();
    }
}