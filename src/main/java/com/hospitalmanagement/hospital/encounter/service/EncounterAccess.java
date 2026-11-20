package com.hospitalmanagement.hospital.encounter.service;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EncounterAccess {
    private final DoctorRepository doctors;
    private final DoctorEligibility eligibility;

    public static UUID actorId(JwtAuthenticationToken authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.INVALID_ACTOR_ID);
        }

        String subject = authentication.getToken().getSubject();

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

    public static boolean hasRole(JwtAuthenticationToken authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(role));
    }

    public static void requireRole(JwtAuthenticationToken authentication, String role) {
        if (!hasRole(authentication, role)) {
            throw new BusinessException(ErrorCode.ENCOUNTER_ACCESS_DENIED);
        }
    }

    public UUID requireActiveDoctor(UUID actor) {
        UUID doctor = doctors.findActiveIdByAccount(actor)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENCOUNTER_ACCESS_DENIED));

        if (!eligibility.isEligibleAccount(actor)) {
            throw new BusinessException(ErrorCode.ENCOUNTER_ACCESS_DENIED);
        }

        return doctor;
    }
}
