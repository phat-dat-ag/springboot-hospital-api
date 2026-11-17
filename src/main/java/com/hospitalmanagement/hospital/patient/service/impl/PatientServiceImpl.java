package com.hospitalmanagement.hospital.patient.service.impl;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.patient.dto.request.ContactRequest;
import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.dto.response.PatientRegistrationResponse;
import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.entity.PatientContact;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;
import com.hospitalmanagement.hospital.patient.enums.ContactType;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientContactRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.PatientService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class PatientServiceImpl implements PatientService {
    private static final String MRN_UNIQUE_CONSTRAINT = "patients_medical_record_number_key";

    private final PatientRepository patientRepository;
    private final PatientContactRepository patientContactRepository;
    private final ClinicalAuditLogRepository clinicalAuditLogRepository;

    @Override
    @Transactional
    public PatientRegistrationResponse registerPatient(
            RegisterPatientRequest request,
            String authenticatedSubject
    ) {
        UUID actorUserId;

        try {
            actorUserId = UUID.fromString(authenticatedSubject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.warn("Invalid actor id: {}", authenticatedSubject);

            throw new BusinessException(ErrorCode.INVALID_ACTOR_ID);
        }

        validateContacts(request.contacts());

        Patient patient = new Patient();

        patient.setMedicalRecordNumber(request.medicalRecordNumber());
        patient.setGivenName(request.givenName());
        patient.setFamilyName(request.familyName());
        patient.setDateOfBirth(request.dateOfBirth());
        patient.setSexAtBirth(request.sexAtBirth());

        try {
            patientRepository.saveAndFlush(patient);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateMedicalRecordNumber(exception)) {
                log.error(
                        "Patient is already existed, exception={}",
                        exception.getMessage()
                );

                // Exit immediately: the failed transaction must roll back.
                throw new BusinessException(ErrorCode.PATIENT_ALREADY_EXISTS);
            }

            throw exception;
        }

        List<PatientContact> savedContacts = patientContactRepository.saveAll(
                request.contacts()
                        .stream()
                        .map(contact -> toContact(patient, contact))
                        .toList()
        );

        log.info(
                "Saved {} patient contacts, patientId={}, contactIds={}",
                savedContacts.size(),
                patient.getId(),
                savedContacts.stream()
                        .map(PatientContact::getId)
                        .toList()
        );

        ClinicalAuditLog event = ClinicalAuditLog.builder()
                .patient(patient)
                .actorUserId(actorUserId)
                .actorType(AuditActorType.USER)
                .operation(AuditOperation.CREATE)
                .entityType("patients")
                .entityId(patient.getId())
                .build();

        ClinicalAuditLog savedEvent = clinicalAuditLogRepository.saveAndFlush(event);

        log.info(
                "Clinical audit event saved: auditId={}, actorUserId={}, "
                        + "actorType={}, operation={}, entityType={}, entityId={}",
                savedEvent.getId(),
                savedEvent.getActorUserId(),
                savedEvent.getActorType(),
                savedEvent.getOperation(),
                savedEvent.getEntityType(),
                savedEvent.getEntityId()
        );

        List<UUID> contactIds = savedContacts.stream()
                .map(PatientContact::getId)
                .toList();

        PatientRegistrationResponse response = new PatientRegistrationResponse(
                patient.getId(),
                patient.getMedicalRecordNumber(),
                patient.getGivenName(),
                patient.getFamilyName(),
                patient.getDateOfBirth(),
                patient.getSexAtBirth(),
                patient.getCreatedAt(),
                contactIds
        );

        log.info(
                "Patient registration completed: patientId={}, contactCount={}",
                response.id(),
                response.contactIds().size()
        );

        return response;
    }

    private void validateContacts(List<ContactRequest> requestedContacts) {
        Set<ContactType> primaryContactTypes = new HashSet<>();

        for (ContactRequest contact : requestedContacts) {
            if (!StringUtils.hasText(contact.phone())
                    && !StringUtils.hasText(contact.email())
                    && !StringUtils.hasText(contact.addressText())
            ) {
                log.warn(
                        "Patient contact validation failed: "
                                + "reason={}, contactType={}",
                        ErrorCode.CONTACT_DETAILS_REQUIRED,
                        contact.contactType()
                );

                throw new BusinessException(
                        ErrorCode.CONTACT_DETAILS_REQUIRED
                );
            }

            if (contact.contactType() != ContactType.SELF
                    && !StringUtils.hasText(contact.contactName())
            ) {
                log.warn(
                        "Patient contact validation failed: "
                                + "reason={}, contactType={}",
                        ErrorCode.CONTACT_NAME_REQUIRED,
                        contact.contactType()
                );

                throw new BusinessException(
                        ErrorCode.CONTACT_NAME_REQUIRED
                );
            }

            if (contact.isPrimary()
                    && !primaryContactTypes.add(contact.contactType())
            ) {
                log.warn(
                        "Patient contact validation failed: "
                                + "reason={}, contactType={}",
                        ErrorCode.DUPLICATE_PRIMARY_CONTACT,
                        contact.contactType()
                );

                throw new BusinessException(
                        ErrorCode.DUPLICATE_PRIMARY_CONTACT
                );
            }
        }
    }

    private PatientContact toContact(Patient patient, ContactRequest request) {
        PatientContact contact = new PatientContact();

        contact.setPatient(patient);
        contact.setContactType(request.contactType());
        contact.setContactName(request.contactName());
        contact.setRelationship(request.relationship());
        contact.setPhone(request.phone());
        contact.setEmail(request.email());
        contact.setAddressText(request.addressText());
        contact.setPrimary(request.isPrimary());

        return contact;
    }

    private boolean isDuplicateMedicalRecordNumber(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && MRN_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())
            ) {
                return true;
            }
        }

        return false;
    }
}
