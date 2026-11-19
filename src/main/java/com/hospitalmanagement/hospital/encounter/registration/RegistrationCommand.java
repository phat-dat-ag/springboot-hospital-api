package com.hospitalmanagement.hospital.encounter.registration;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.dto.request.RegisterEncounterRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

record RegistrationCommand(
        UUID actorId,
        String key,
        UUID patientId,
        UUID doctorId,
        String chiefComplaint,
        Instant requestedAt
) {
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    static RegistrationCommand from(String subject, String key, RegisterEncounterRequest request) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()
                || request.chiefComplaint().indexOf(0) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        Instant requested = request.scheduledAt();

        return new RegistrationCommand(
                parseActor(subject),
                key,
                request.patientId(),
                request.doctorId(),
                request.chiefComplaint().strip(),
                requested == null ? null : requested.truncatedTo(ChronoUnit.MICROS)
        );
    }

    byte[] fingerprint() {
        String canonical = "v1\n" + patientId + "\n" + doctorId + "\n"
                + (requestedAt == null ? "walk-in" : requestedAt) + "\n" + chiefComplaint;

        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static UUID parseActor(String subject) {
        try {
            UUID actor = UUID.fromString(subject);

            if (actor.toString().equalsIgnoreCase(subject)) {
                return actor;
            }
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new BusinessException(ErrorCode.INVALID_ACTOR_ID);
        }

        throw new BusinessException(ErrorCode.INVALID_ACTOR_ID);
    }
}
