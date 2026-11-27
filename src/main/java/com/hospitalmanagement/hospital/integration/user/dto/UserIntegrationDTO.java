package com.hospitalmanagement.hospital.integration.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserIntegrationDTO(
        UUID id,
        String username,
        int role,
        String email,
        boolean isActivated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
