package com.hospitalmanagement.hospital.encounter.registration;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
import com.hospitalmanagement.hospital.encounter.dto.request.RegisterEncounterRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterRegistrationResponse;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class EncounterRegistrationService {
    private static final LocalDate LAST_SCHEDULE_DATE = LocalDate.of(9999, 12, 31);

    private final EncounterRegistrationStore store;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final DoctorEligibility eligibility;
    private final Clock clinicClock;

    public Result register(String subject, String key, RegisterEncounterRequest request) {
        RegistrationCommand command = RegistrationCommand.from(subject, key, request);

        byte[] digest = command.fingerprint();

        try {
            var replay = store.replay(command, digest);
            if (replay.isPresent()) {
                return new Result(replay.get(), true);
            }

            Instant now = clinicClock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate today = now.atZone(clinicClock.getZone()).toLocalDate();
            Instant scheduled = command.requestedAt() == null ? now : command.requestedAt();
            validateSchedule(scheduled, today);

            if (patients.findById(command.patientId())
                    .filter(patient -> patient.getDeletedAt() == null).isEmpty()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }

            var doctor = doctors.findById(command.doctorId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            eligibility.requiresEligibleDoctorProfile(doctor, today);
            eligibility.requiresEligibleDoctorAccount(doctor.getExternalUserId());

            Result result = store.register(
                    command, digest, doctor.getExternalUserId(), scheduled
            );

            log.atInfo()
                    .addKeyValue("event", result.replay()
                            ? "encounter.registration.replayed" : "encounter.registration.completed")
                    .log("Encounter registration request completed");

            return result;
        } catch (PessimisticLockingFailureException failure) {
            throw new BusinessException(ErrorCode.ENCOUNTER_LOCK_CONFLICT);
        } catch (DataAccessException | TransactionException failure) {
            log.atError()
                    .addKeyValue("event", "encounter.registration.failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Encounter registration persistence failed");
            throw new BusinessException(ErrorCode.ENCOUNTER_REGISTRATION_FAILED);
        }
    }

    private void validateSchedule(Instant scheduled, LocalDate today) {
        var zone = clinicClock.getZone();

        if (scheduled.isBefore(today.atStartOfDay(zone).toInstant())
                || !scheduled.isBefore(LAST_SCHEDULE_DATE.plusDays(1).atStartOfDay(zone).toInstant())) {
            throw new BusinessException(ErrorCode.ENCOUNTER_SCHEDULE_INVALID);
        }
    }

    public record Result(EncounterRegistrationResponse encounter, boolean replay) {
    }
}
