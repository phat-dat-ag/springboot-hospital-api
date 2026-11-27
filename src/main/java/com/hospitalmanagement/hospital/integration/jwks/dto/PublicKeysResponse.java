package com.hospitalmanagement.hospital.integration.jwks.dto;

import java.util.List;

public record PublicKeysResponse(
        List<PublicKeyDTO> keys
) {
}