package com.hospitalmanagement.hospital.integration.jwks.service.impl;

import com.hospitalmanagement.hospital.integration.jwks.client.JwksServiceClient;
import com.hospitalmanagement.hospital.integration.jwks.dto.PublicKeysResponse;
import com.hospitalmanagement.hospital.integration.jwks.service.JwksIntegrationService;
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