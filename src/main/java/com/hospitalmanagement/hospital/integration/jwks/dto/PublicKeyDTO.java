package com.hospitalmanagement.hospital.integration.jwks.dto;

public record PublicKeyDTO(
        String kty,
        String use,
        String alg,
        String kid,
        String n,
        String e
) {
}
