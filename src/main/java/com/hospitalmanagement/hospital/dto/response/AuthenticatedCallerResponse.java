package com.hospitalmanagement.hospital.dto.response;

import java.util.List;

public record AuthenticatedCallerResponse(
        String userId,
        List<String> authorities,
        String issuer
) {
}
