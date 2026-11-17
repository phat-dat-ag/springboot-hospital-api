package com.hospitalmanagement.hospital.service;

import com.hospitalmanagement.hospital.dto.response.PublicKeysResponse;

public interface JwksIntegrationService {

    PublicKeysResponse getPublicKeys();
}