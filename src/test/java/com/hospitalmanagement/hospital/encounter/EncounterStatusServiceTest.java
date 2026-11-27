package com.hospitalmanagement.hospital.encounter;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.integration.user.client.UserServiceClient;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
import com.hospitalmanagement.hospital.encounter.service.EncounterAccess;
import com.hospitalmanagement.hospital.encounter.dto.request.ChangeEncounterStatusRequest;
import com.hospitalmanagement.hospital.encounter.entity.Doctor;
import com.hospitalmanagement.hospital.encounter.entity.EncounterStatusHistory;
import com.hospitalmanagement.hospital.encounter.entity.MedicalEncounter;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;
import com.hospitalmanagement.hospital.encounter.repository.EncounterStatusHistoryRepository;
import com.hospitalmanagement.hospital.encounter.repository.MedicalEncounterRepository;
import com.hospitalmanagement.hospital.encounter.service.impl.EncounterStatusServiceImpl;
import com.hospitalmanagement.hospital.logging.StructuredJsonEncoder;
import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static com.hospitalmanagement.hospital.encounter.EncounterTestAuthentication.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EncounterStatusServiceTest {
    // Bangkok is already November 19; UTC is still November 18.
    private static final Instant NOW = Instant.parse("2026-11-18T18:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();
    private final MedicalEncounterRepository encounters = mock(MedicalEncounterRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final DoctorRepository doctors = mock(DoctorRepository.class);
    private final EncounterStatusHistoryRepository histories =
            mock(EncounterStatusHistoryRepository.class);
    private final ClinicalAuditLogRepository audits = mock(ClinicalAuditLogRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final MedicalEncounter encounter = mock(MedicalEncounter.class);
    private final Patient patient = mock(Patient.class);
    private final Doctor doctor = mock(Doctor.class);
    private final EncounterAccess access = mock(EncounterAccess.class);
    private final DoctorEligibility eligibility = new DoctorEligibility(mock(UserServiceClient.class));
    private final EncounterStatusServiceImpl service = new EncounterStatusServiceImpl(
            encounters, patients, doctors, histories, audits, entityManager,
            Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")), access, eligibility
    );

    @BeforeEach
    void prepareLockedRows() {
        Query query = mock(Query.class);

        given(entityManager.createNativeQuery(any(String.class))).willReturn(query);
        given(encounters.findByIdForUpdate(ID)).willReturn(Optional.of(encounter));
        given(encounter.getStatus()).willReturn(EncounterStatus.ARRIVED);
        given(encounter.getPatient()).willReturn(patient);
        given(patient.getId()).willReturn(PATIENT);
        given(patients.findByIdForUpdate(PATIENT)).willReturn(Optional.of(patient));
        given(encounter.getDoctor()).willReturn(doctor);
        given(doctor.getId()).willReturn(DOCTOR);
        given(doctor.getExternalUserId()).willReturn(UUID.fromString(ACTOR));
        given(access.requireActiveDoctor(UUID.fromString(ACTOR))).willReturn(DOCTOR);
        given(doctors.findByIdForUpdate(DOCTOR)).willReturn(Optional.of(doctor));
    }

    @Test
    void logsAllStagesAtInfoAndOnlyClaimsSuccessAfterCommitWithoutPrivateValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(EncounterStatusServiceImpl.class);

        Level previousLevel = logger.getLevel();

        ListAppender<ILoggingEvent> appender = new ListAppender<>();

        StructuredJsonEncoder encoder = new StructuredJsonEncoder();

        appender.start();

        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        encoder.setContext(logger.getLoggerContext());
        encoder.start();

        TransactionSynchronizationManager.initSynchronization();

        try {
            service.changeStatus(
                    ID,
                    new ChangeEncounterStatusRequest(
                            EncounterStatus.ARRIVED,
                            EncounterStatus.IN_PROGRESS,
                            "private reason"
                    ),
                    authentication(ACTOR, "ROLE_DOCTOR")
            );

            assertThat(appender.list).hasSize(6);
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));

            String beforeCommit = encoded(encoder, appender);

            assertThat(beforeCommit).contains(
                    "encounter.status.started",
                    "encounter.status.lock.started",
                    "encounter.status.validated",
                    "encounter.status.history.prepared",
                    "encounter.status.audit.prepared",
                    "encounter.status.flushed"
            ).doesNotContain("encounter.status.completed");

            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()
            ) {
                synchronization.afterCommit();
            }

            assertThat(encoded(encoder, appender))
                    .contains(
                            "encounter.status.completed",
                            "encounter.from_status",
                            "encounter.to_status",
                            "in_progress"
                    )
                    .doesNotContain(ACTOR, ID.toString(), PATIENT.toString(), "private reason");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();

            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            encoder.stop();
        }
    }

    private String encoded(
            StructuredJsonEncoder encoder,
            ListAppender<ILoggingEvent> appender
    ) {
        StringBuilder json = new StringBuilder();

        for (ILoggingEvent event : appender.list) {
            json.append(new String(encoder.encode(event), StandardCharsets.UTF_8));
        }

        return json.toString();
    }

    @Test
    void rejectsActorWithoutTouchingDatabase() {
        assertError(
                ErrorCode.INVALID_ACTOR_ID,
                () -> service.changeStatus(ID, start(), authentication("private-invalid-subject", "ROLE_DOCTOR"))
        );

        verifyNoInteractions(encounters, patients, doctors, histories, audits, entityManager);
    }

    @Test
    void rejectsMissingCancellationReasonBeforeLocking() {
        assertError(ErrorCode.ENCOUNTER_REASON_REQUIRED, () -> service.changeStatus(
                ID,
                new ChangeEncounterStatusRequest(
                        EncounterStatus.ARRIVED, EncounterStatus.CANCELLED, " \t "
                ),
                authentication(ACTOR, "ROLE_STAFF")
        ));
        verifyNoInteractions(encounters, histories, audits);
    }

    @Test
    void rejectsUnknownEncounter() {
        given(encounters.findByIdForUpdate(ID)).willReturn(Optional.empty());

        assertError(
                ErrorCode.ENCOUNTER_NOT_FOUND,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );
    }

    @Test
    void rejectsStaleExpectedStatus() {
        given(encounter.getStatus()).willReturn(EncounterStatus.IN_PROGRESS);

        assertError(
                ErrorCode.ENCOUNTER_STATUS_CONFLICT,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );

        verifyNoInteractions(histories, audits);
    }

    @Test
    void rejectsInactivePatient() {
        given(patient.getDeletedAt()).willReturn(NOW);

        assertError(
                ErrorCode.ENCOUNTER_PATIENT_INACTIVE,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );

        verifyNoInteractions(doctors, histories, audits);
    }

    @Test
    void rejectsMissingDoctor() {
        given(encounter.getDoctor()).willReturn(null);

        assertError(
                ErrorCode.ENCOUNTER_NOT_FOUND,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );
    }

    @Test
    void rejectsSoftDeletedDoctor() {
        given(doctor.getDeletedAt()).willReturn(NOW);

        assertError(
                ErrorCode.ENCOUNTER_NOT_FOUND,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );
    }

    @Test
    void usesClinicDateForLicenseExpiry() {
        given(doctor.getLicenseExpiresOn()).willReturn(LocalDate.of(2026, 11, 18));

        assertError(
                ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );

        verifyNoInteractions(histories, audits);
    }

    @Test
    void acceptsLicenseExpiringTodayAndSharesOneEventInstant() {
        given(doctor.getLicenseExpiresOn()).willReturn(LocalDate.of(2026, 11, 19));

        service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"));

        verify(encounter).changeStatus(EncounterStatus.IN_PROGRESS, null, NOW);

        var history = ArgumentCaptor.forClass(EncounterStatusHistory.class);
        var audit = ArgumentCaptor.forClass(ClinicalAuditLog.class);

        verify(histories).save(history.capture());
        verify(audits).save(audit.capture());

        assertThat(history.getValue().getOccurredAt()).isEqualTo(NOW);
        assertThat(audit.getValue().getOccurredAt()).isEqualTo(NOW);
        assertThat(audit.getValue().getOperation()).isEqualTo(AuditOperation.UPDATE);

        verify(entityManager).flush();
        verify(entityManager).refresh(encounter);
    }

    @Test
    void missingExpiryMeansUnspecifiedAndIsAccepted() {
        service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"));

        verify(histories).save(any(EncounterStatusHistory.class));
    }

    @Test
    void requiresCompletionEvidenceAndRejectsReceptionAndAdmin() {
        given(encounter.getStatus()).willReturn(EncounterStatus.IN_PROGRESS);
        given(encounter.getStartedAt()).willReturn(NOW);

        var complete = new ChangeEncounterStatusRequest(
                EncounterStatus.IN_PROGRESS, EncounterStatus.COMPLETED, null
        );

        assertError(
                ErrorCode.ENCOUNTER_SIGNED_ASSESSMENT_REQUIRED,
                () -> service.changeStatus(ID, complete, authentication(ACTOR, "ROLE_DOCTOR"))
        );

        assertError(ErrorCode.ENCOUNTER_ACCESS_DENIED, () -> service.changeStatus(
                ID, complete, authentication(ACTOR, "ROLE_ADMIN")
        ));

        assertError(
                ErrorCode.ENCOUNTER_ACCESS_DENIED,
                () -> service.changeStatus(ID, complete, authentication(ACTOR, "ROLE_STAFF"))
        );

        verifyNoInteractions(histories, audits);
    }

    @Test
    void mapsLockFailureToSafeConflictWithoutRetry() {
        given(encounters.findByIdForUpdate(ID))
                .willThrow(new CannotAcquireLockException("private SQL"));

        assertError(
                ErrorCode.ENCOUNTER_LOCK_CONFLICT,
                () -> service.changeStatus(ID, start(), authentication(ACTOR, "ROLE_DOCTOR"))
        );

        verify(encounters).findByIdForUpdate(ID);
        verifyNoInteractions(histories, audits);
    }

    private ChangeEncounterStatusRequest start() {
        return new ChangeEncounterStatusRequest(
                EncounterStatus.ARRIVED, EncounterStatus.IN_PROGRESS, null
        );
    }

    private void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}

