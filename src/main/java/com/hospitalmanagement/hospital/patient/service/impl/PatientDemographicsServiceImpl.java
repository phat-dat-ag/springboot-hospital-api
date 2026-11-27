package com.hospitalmanagement.hospital.patient.service.impl;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.common.utils.UUIDUtils;
import com.hospitalmanagement.hospital.patient.dto.response.PatientDemographicsResponse;
import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOutcome;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.PatientDemographicsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientDemographicsServiceImpl implements PatientDemographicsService {
    private static final String PATIENT_ENTITY = "patients";
    private static final String VIEW_PURPOSE = "patient_identity_verification";

    private final PatientRepository patientRepository;
    private final MedicalRecordAccessLogRepository medicalRecordAccessLogRepository;

    @Override
    @Transactional
    public PatientDemographicsResponse getDemographics(UUID patientId, String subject) {
        log.atInfo()
                .addKeyValue("event", "patient.demographics.started")
                .log("Patient demographics lookup started");

        UUID userId = UUIDUtils.parseUserId(subject);

        PatientDemographicsResponse response = findPatientDemographicsResponse(patientId);

        Patient patient = patientRepository.getReferenceById(patientId);

        MedicalRecordAccessLog saveMedicalRecordAccessLog =
                buildMedicalRecordAccessLogRepository(patient, userId, patientId);

        saveMedicalRecordAccessLog(saveMedicalRecordAccessLog);

        logAfterCommit();

        return response;
    }

    private PatientDemographicsResponse findPatientDemographicsResponse(UUID patientId) {
        return patientRepository
                .findDemographicsByIdAndDeletedAtIsNull(patientId)
                .orElseThrow(() -> {
                    log.atInfo()
                            .addKeyValue("event", "patient.demographics.unavailable")
                            .log("Patient demographics unavailable");

                    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                });
    }

    private MedicalRecordAccessLog buildMedicalRecordAccessLogRepository(
            Patient patient,
            UUID userId,
            UUID patientId
    ) {
        return MedicalRecordAccessLog.builder()
                .patient(patient)
                .actorUserId(userId)
                .actorType(AuditActorType.USER)
                .operation(RecordAccessOperation.VIEW)
                .entityType(PATIENT_ENTITY)
                .entityId(patientId)
                .purposeCode(VIEW_PURPOSE)
                .outcome(RecordAccessOutcome.ALLOWED)
                .correlationId(UUID.randomUUID())
                .build();
    }

    private void saveMedicalRecordAccessLog(
            MedicalRecordAccessLog medicalRecordAccessLog
    ) {
        try {
            medicalRecordAccessLogRepository.saveAndFlush(medicalRecordAccessLog);
        } catch (DataAccessException exception) {
            log.atError()
                    .addKeyValue("event", "patient.demographics.access_log.failed")
                    .addKeyValue("errorCode", ErrorCode.PATIENT_ACCESS_LOG_FAILED.name())
                    .log("Patient demographics access logging failed");

            throw new BusinessException(ErrorCode.PATIENT_ACCESS_LOG_FAILED);
        }
    }

    private void logAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.atInfo()
                                .addKeyValue("event", "patient.demographics.completed")
                                .log("Patient demographics lookup committed");
                    }
                }
        );
    }
}
