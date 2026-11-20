package com.hospitalmanagement.hospital.encounter.service.impl;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
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
import com.hospitalmanagement.hospital.encounter.service.EncounterAccess;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final EncounterAccess access;
    private final DoctorEligibility eligibility;

    @Override
    @Transactional
    public EncounterStatusResponse changeStatus(
            UUID id,
            ChangeEncounterStatusRequest request,
            JwtAuthenticationToken authentication
    ) {
        stage("started", "validation");

        UUID actor = EncounterAccess.actorId(authentication);
        authorizeTarget(request.status(), authentication);

        if (request.status() == EncounterStatus.CANCELLED
                && (request.reason() == null || request.reason().isBlank())
        ) {
            throw rejected(ErrorCode.ENCOUNTER_REASON_REQUIRED);
        }

        UUID doctorId = request.status() == EncounterStatus.IN_PROGRESS
                ? access.requireActiveDoctor(actor) : null;

        try {
            return changeLocked(id, request, actor, doctorId);
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
            UUID actor,
            UUID doctorId
    ) {
        // PostgreSQL transaction-local bound applies to every row/FK lock below.
        entityManager.createNativeQuery("select set_config('lock_timeout', '3s', true)")
                .getSingleResult();

        stage("lock.started", "encounter_lock");

        MedicalEncounter encounter = encounters.findByIdForUpdate(id)
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_NOT_FOUND));

        if (doctorId != null && (encounter.getDoctor() == null
                || !doctorId.equals(encounter.getDoctor().getId()))) {
            throw rejected(ErrorCode.ENCOUNTER_NOT_FOUND);
        }

        // Fixed order: encounter -> patient -> doctor. No collection joins under lock.
        Patient patient = patients.findByIdForUpdate(encounter.getPatient().getId())
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_PATIENT_INACTIVE));

        if (patient.getDeletedAt() != null) {
            throw rejected(ErrorCode.ENCOUNTER_PATIENT_INACTIVE);
        }

        Instant instant = clinicClock.instant();

        if (doctorId != null) {
            validateDoctor(doctorId, actor, instant);
        }

        EncounterStatus previous = encounter.getStatus();

        if (previous != request.expectedStatus()) {
            throw rejected(ErrorCode.ENCOUNTER_STATUS_CONFLICT);
        }

        if (!EncounterTransitions.allows(previous, request.status())) {
            throw rejected(ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED);
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

    private void validateDoctor(UUID doctorId, UUID actor, Instant instant) {
        Doctor doctor = doctors.findByIdForUpdate(doctorId)
                .orElseThrow(() -> rejected(ErrorCode.ENCOUNTER_NOT_FOUND));

        if (doctor.getDeletedAt() != null || !actor.equals(doctor.getExternalUserId())) {
            throw rejected(ErrorCode.ENCOUNTER_NOT_FOUND);
        }

        LocalDate clinicDate = instant.atZone(clinicClock.getZone()).toLocalDate();
        eligibility.requireEligibleProfile(doctor, clinicDate);
    }

    private void authorizeTarget(EncounterStatus target, JwtAuthenticationToken authentication) {
        if (!EncounterAccess.hasRole(authentication, "ROLE_STAFF")
                && !EncounterAccess.hasRole(authentication, "ROLE_DOCTOR")) {
            throw rejected(ErrorCode.ENCOUNTER_ACCESS_DENIED);
        }

        switch (target) {
            case ARRIVED, CANCELLED -> EncounterAccess.requireRole(authentication, "ROLE_STAFF");
            case IN_PROGRESS -> EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");
            default -> throw rejected(ErrorCode.ENCOUNTER_TRANSITION_NOT_ALLOWED);
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
