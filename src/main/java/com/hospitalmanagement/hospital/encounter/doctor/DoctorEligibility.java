package com.hospitalmanagement.hospital.encounter.doctor;

import com.hospitalmanagement.hospital.client.UserServiceClient;
import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.entity.Doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class DoctorEligibility {
    private static final int DOCTOR_ROLE = 4;
    private static final int MAX_CONCURRENT_LOOKUPS = 8;

    private final UserServiceClient accounts;
    private final Semaphore lookups = new Semaphore(MAX_CONCURRENT_LOOKUPS);

    public void requireEligibleAccount(UUID accountId) {
        if (!lookups.tryAcquire()) {
            throw new BusinessException(ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE);
        }

        try {
            var account = accounts.getAccountIdentity(accountId);

            if (!account.isActivated()) {
                throw new BusinessException(ErrorCode.DOCTOR_ACCOUNT_INACTIVE);
            }

            if (account.role() != DOCTOR_ROLE) {
                throw new BusinessException(ErrorCode.DOCTOR_ACCOUNT_ROLE_INVALID);
            }
        } finally {
            lookups.release();
        }
    }

    public boolean isEligibleAccount(UUID accountId) {
        try {
            requireEligibleAccount(accountId);
            return true;
        } catch (BusinessException failure) {
            return switch (failure.getErrorCode()) {
                case DOCTOR_ACCOUNT_NOT_FOUND, DOCTOR_ACCOUNT_INACTIVE,
                     DOCTOR_ACCOUNT_ROLE_INVALID -> false;
                default -> throw failure;
            };
        }
    }

    public void requireEligibleProfile(Doctor doctor, LocalDate date) {
        if (doctor.getDeletedAt() != null
                || doctor.getExternalUserId() == null
                || (doctor.getLicenseExpiresOn() != null
                && doctor.getLicenseExpiresOn().isBefore(date))
        ) {
            throw new BusinessException(ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE);
        }
    }
}
