package com.hospitalmanagement.hospital.dto.response;

import java.util.List;

public record PublicKeysResponse(
        List<PublicKeyDTO> keys
) {
}