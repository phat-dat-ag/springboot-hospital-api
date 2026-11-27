package com.hospitalmanagement.hospital.common.utils;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class UUIDUtils {
    public static UUID parseUserId(String authenticatedSubject) {
        try {
            UUID userId = UUID.fromString(authenticatedSubject);

            if (userId.toString().equalsIgnoreCase(authenticatedSubject)) {
                return userId;
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw RejectRequestUtils.reject(
                    "parse.userId",
                    "validation",
                    "Failed to parse userId: " + exception.getMessage(),
                    ErrorCode.INVALID_ACTOR_ID
            );
        }

        throw new BusinessException(ErrorCode.INVALID_ACTOR_ID);
    }
}
