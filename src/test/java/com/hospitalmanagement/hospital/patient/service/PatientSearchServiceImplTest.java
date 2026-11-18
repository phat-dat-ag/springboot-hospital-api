package com.hospitalmanagement.hospital.patient.service;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;
import com.hospitalmanagement.hospital.patient.service.impl.PatientSearchServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PatientSearchServiceImplTest {
    private final PatientRepository patients = mock(PatientRepository.class);
    private final MedicalRecordAccessLogRepository logs =
            mock(MedicalRecordAccessLogRepository.class);
    private final PatientSearchServiceImpl service = new PatientSearchServiceImpl(patients, logs);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", " "})
    void rejectsInvalidActorsBeforeAnyDatabaseAccess(String subject) {
        PatientSearchRequest request = new PatientSearchRequest(
                "EXACT", null, null, null, null, null
        );

        assertThatThrownBy(() -> service.searchPatients(request, subject))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ACTOR_ID));
        verifyNoInteractions(patients, logs);
    }

    @Test
    void refusesUnfilteredSearchBeforeAnyDatabaseAccess() {
        PatientSearchRequest request = new PatientSearchRequest(
                null, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.searchPatients(request, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PATIENT_SEARCH_FILTER_REQUIRED));
        verifyNoInteractions(patients, logs);
    }

    @Test
    void rejectsUnsupportedJpaOffsetBeforeAnyDatabaseAccess() {
        PatientSearchRequest request = new PatientSearchRequest(
                "EXACT", null, null, Integer.MAX_VALUE, 100, null
        );

        assertThatThrownBy(() -> service.searchPatients(request, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PATIENT_SEARCH_PAGE_OUT_OF_RANGE));
        verifyNoInteractions(patients, logs);
    }
}
