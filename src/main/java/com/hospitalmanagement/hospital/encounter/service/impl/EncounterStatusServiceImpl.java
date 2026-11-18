package com.hospitalmanagement.hospital.encounter.service.impl;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterStatusResponse;
import com.hospitalmanagement.hospital.encounter.entity.Doctor;
import com.hospitalmanagement.hospital.encounter.entity.EncounterStatusHistory;
import com.hospitalmanagement.hospital.encounter.entity.MedicalEncounter;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;
import com.hospitalmanagement.hospital.encounter.repository.EncounterStatusHistoryRepository;
import com.hospitalmanagement.hospital.encounter.repository.MedicalEncounterRepository;
import com.hospitalmanagement.hospital.encounter.service.EncounterStatusService;
import com.hospitalmanagement.hospital.encounter.service.EncounterTransitions;
import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class EncounterStatusServiceImpl implements EncounterStatusService {
    private final MedicalEncounterRepository encounters;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final EncounterStatusHistoryRepository histories;
    private final ClinicalAuditLogRepository audits;
    private final EntityManager entityManager;
    private final Clock clinicClock;

    @Override
    @Transactional
    public EncounterStatusResponse changeStatus(
            UUID id,
            ChangeEncounterStatusRequest request,
            String authenticatedSubject
    ) {
        stage("started", "validation");

        UUID actor = actorId(authenticatedSubject);

        if (request.status() == EncounterStatus.CANCELLED
                && (request.reason() == null || request.reason().isBlank())
        ) {
            throw rejected(ErrorCode.ENCOUNTER_REASON_REQUIRED);
        }

        try {
            return changeLocked(id, request, actor);
        } catch (PessimisticLockingFailureException
                 | LockTimeoutException
                 | PessimisticLockException exception
        ) {
            throw rejected(ErrorCode.ENCOUNTER_LOCK_CONFLICT);
        } catch (DataAccessException | PersistenceException exception) {
            log.atError()
                    .addKeyValue("event", "encounter.status.persistence.failed")
                    .addKeyValue("stage", "persistence")
                    .addKeyValue("errorCode", ErrorCode.ENCOUNTER_CHANGE_FAILED.name())
                    .addKeyValue("error.type", exception.getClass().getName())
                    .log("Encounter status persistence failed");

            throw new BusinessException(ErrorCode.ENCOUNTER_CHANGE_FAILED);
        }
    }

    private EncounterStatusResponse changeLocked(
            UUID id,
            ChangeEncounterStatusRequest request,
            UUID actor
    ) {
        // PostgreSQL transaction-local bound applies to every row/FK lock below.
        entityManager.createNativeQuery("select set_config('lock_timeout', '3s', true)")
                .getSingleResult();

        stage("lock.started", "encounter_lock");

        MedicalEncounter encounter = encounters.findByIdForUpdate(id)
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_NOT_FOUND));

        EncounterStatus previous = encounter.getStatus();

        if (previous != request.expectedStatus()) {
            throw rejected(ErrorCode.ENCOUNTER_STATUS_CONFLICT);
        }

        if (!EncounterTransitions.allows(previous, request.status())) {
            throw rejected(ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED);
        }

        // Fixed order: encounter -> patient -> doctor. No collection joins under lock.
        Patient patient = patients.findByIdForUpdate(encounter.getPatient().getId())
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_PATIENT_INACTIVE));

        if (patient.getDeletedAt() != null) {
            throw rejected(ErrorCode.ENCOUNTER_PATIENT_INACTIVE);
        }

        Instant instant = clinicClock.instant();

        if (request.status() == EncounterStatus.IN_PROGRESS) {
            validateDoctor(encounter, instant);
        }

        if (request.status() == EncounterStatus.COMPLETED
                && (encounter.getDoctor() == null
                || encounter.getStartedAt() == null
                || instant.isBefore(encounter.getStartedAt()))
        ) {
            throw rejected(ErrorCode.ENCOUNTER_START_REQUIRED);
        }

        stage("validated", "transition_validation");
        encounter.changeStatus(request.status(), request.reason(), instant);

        histories.save(EncounterStatusHistory.builder()
                .encounter(encounter)
                .fromStatus(previous)
                .toStatus(request.status())
                .actorUserId(actor)
                .reason(request.reason())
                .occurredAt(instant)
                .build());
        stage("history.prepared", "history");

        audits.save(ClinicalAuditLog.builder()
                .patient(patient)
                .actorUserId(actor)
                .actorType(AuditActorType.USER)
                .operation(request.status() == EncounterStatus.CANCELLED
                        ? AuditOperation.CANCEL : AuditOperation.UPDATE)
                .entityType("medical_encounters")
                .entityId(encounter.getId())
                .occurredAt(instant)
                .build());
        stage("audit.prepared", "audit");

        entityManager.flush();
        entityManager.refresh(encounter);

        stage("flushed", "awaiting_commit");
        logAfterCommit(previous, request.status());

        return new EncounterStatusResponse(
                encounter.getId(),
                patient.getId(),
                encounter.getStatus(),
                encounter.getStartedAt(),
                encounter.getEndedAt(),
                encounter.getUpdatedAt()
        );
    }

    private void validateDoctor(MedicalEncounter encounter, Instant instant) {
        if (encounter.getDoctor() == null) {
            throw rejected(ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE);
        }

        Doctor doctor = doctors.findByIdForUpdate(encounter.getDoctor().getId())
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE));

        LocalDate clinicDate = instant.atZone(clinicClock.getZone()).toLocalDate();

        if (doctor.getDeletedAt() != null
                || (doctor.getLicenseExpiresOn() != null
                && doctor.getLicenseExpiresOn().isBefore(clinicDate))
        ) {
            throw rejected(ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE);
        }
    }

    private UUID actorId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw rejected(ErrorCode.INVALID_ACTOR_ID);
        }
    }

    private BusinessException rejected(ErrorCode errorCode) {
        log.atWarn()
                .addKeyValue("event", "encounter.status.rejected")
                .addKeyValue("stage", "validation")
                .addKeyValue("errorCode", errorCode.name())
                .log("Encounter status change rejected");

        return new BusinessException(errorCode);
    }

    private void stage(String event, String stage) {
        log.atInfo()
                .addKeyValue("event", "encounter.status." + event)
                .addKeyValue("stage", stage)
                .log("Encounter status workflow stage");
    }

    private void logAfterCommit(EncounterStatus previous, EncounterStatus target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.atInfo()
                        .addKeyValue("event", "encounter.status.completed")
                        .addKeyValue("stage", "committed")
                        .addKeyValue("encounter.from_status", previous.getValue())
                        .addKeyValue("encounter.to_status", target.getValue())
                        .log("Encounter status change committed");
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    log.atError()
                            .addKeyValue("event", "encounter.status.transaction.failed")
                            .addKeyValue("stage", "transaction_not_committed")
                            .log("Encounter status transaction did not commit");
                }
            }
        });
    }
}
