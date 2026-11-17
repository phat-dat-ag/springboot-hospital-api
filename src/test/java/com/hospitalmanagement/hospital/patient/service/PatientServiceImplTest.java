package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.patient.dto.request.RegisterPatientRequest;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientContactRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.impl.PatientServiceImpl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PatientServiceImplTest {
    private final PatientRepository patients = mock(PatientRepository.class);
    private final PatientContactRepository contacts = mock(PatientContactRepository.class);
    private final ClinicalAuditLogRepository auditLogs = mock(ClinicalAuditLogRepository.class);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", " "})
    void rejectsInvalidActorBeforeWriting(String authenticatedSubject) {
        var service = new PatientServiceImpl(patients, contacts, auditLogs);
        var request = new RegisterPatientRequest(
                "LEARN-0001",
                "Example",
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.registerPatient(request, authenticatedSubject))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACTOR_ID));
        verifyNoInteractions(patients, contacts, auditLogs);
    }
}
