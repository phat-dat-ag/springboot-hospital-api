package com.hospitalmanagement.hospital.integration.jwks.service;

import com.hospitalmanagement.hospital.integration.jwks.dto.PublicKeysResponse;

public interface JwksIntegrationService {

    PublicKeysResponse getPublicKeys();
}