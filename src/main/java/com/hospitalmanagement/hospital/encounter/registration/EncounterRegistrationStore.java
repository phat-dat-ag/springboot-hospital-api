package com.hospitalmanagement.hospital.encounter.registration;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
import com.hospitalmanagement.hospital.encounter.dto.response.EncounterRegistrationResponse;
import com.hospitalmanagement.hospital.encounter.entity.EncounterStatusHistory;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;
import com.hospitalmanagement.hospital.encounter.repository.EncounterStatusHistoryRepository;
import com.hospitalmanagement.hospital.encounter.repository.MedicalEncounterRepository;
import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class EncounterRegistrationStore {
    private final JdbcClient jdbc;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final MedicalEncounterRepository encounters;
    private final EncounterStatusHistoryRepository histories;
    private final ClinicalAuditLogRepository audits;
    private final DoctorEligibility eligibility;
    private final Clock clinicClock;

    Optional<EncounterRegistrationResponse> replay(RegistrationCommand command, byte[] digest) {
        return jdbc.sql("""
                        SELECT k.request_digest, e.id, e.encounter_number, e.patient_id, e.doctor_id,
                               e.scheduled_at, e.created_at, p.deleted_at
                        FROM encounter_registration_keys k
                        JOIN medical_encounters e ON e.id = k.encounter_id
                        JOIN patients p ON p.id = e.patient_id
                        WHERE k.actor_user_id = :actor AND k.idempotency_key = :key
                        """)
                .param("actor", command.actorId())
                .param("key", command.key())
                .query((row, index) -> {
                    if (!MessageDigest.isEqual(digest, row.getBytes("request_digest"))) {
                        throw new BusinessException(ErrorCode.ENCOUNTER_IDEMPOTENCY_CONFLICT);
                    }

                    if (row.getObject("deleted_at") != null) {
                        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                    }

                    return header(row);
                })
                .optional();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncounterRegistrationService.Result register(
            RegistrationCommand command,
            byte[] digest,
            UUID verifiedAccount,
            Instant scheduledAt
    ) {
        jdbc.sql("select set_config('lock_timeout', '3s', true)").query(String.class).single();
        UUID encounterId = UUID.randomUUID();

        if (!claim(command, digest, encounterId)) {
            return new EncounterRegistrationService.Result(
                    replay(command, digest).orElseThrow(), true
            );
        }

        Patient patient = patients.findByIdForUpdate(command.patientId())
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var doctor = doctors.findByIdForUpdate(command.doctorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        LocalDate today = LocalDate.now(clinicClock);
        eligibility.requireEligibleProfile(doctor, today);

        if (!verifiedAccount.equals(doctor.getExternalUserId())) {
            throw new BusinessException(ErrorCode.ENCOUNTER_DOCTOR_UNAVAILABLE);
        }

        var zone = clinicClock.getZone();
        LocalDate visitDate = scheduledAt.atZone(zone).toLocalDate();

        if (visitDate.isBefore(today)) {
            throw new BusinessException(ErrorCode.ENCOUNTER_SCHEDULE_INVALID);
        }

        if (hasOpenVisit(patient.getId(), visitDate)) {
            throw new BusinessException(ErrorCode.ENCOUNTER_OPEN_VISIT);
        }

        var encounter = insertEncounter(command, encounterId, scheduledAt);
        recordCreation(command, patient, encounter);

        return new EncounterRegistrationService.Result(encounter, false);
    }

    private boolean claim(RegistrationCommand command, byte[] digest, UUID encounterId) {
        return jdbc.sql("""
                        INSERT INTO encounter_registration_keys (
                            actor_user_id, idempotency_key, request_digest, encounter_id
                        )
                        VALUES (:actor, :key, :digest, :encounter)
                        ON CONFLICT (actor_user_id, idempotency_key) DO NOTHING
                        """)
                .param("actor", command.actorId())
                .param("key", command.key())
                .param("digest", digest)
                .param("encounter", encounterId)
                .update() == 1;
    }

    private boolean hasOpenVisit(UUID patientId, LocalDate day) {
        var zone = clinicClock.getZone();

        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM medical_encounters
                            WHERE patient_id = :patient
                              AND status IN ('scheduled', 'arrived', 'in_progress')
                              AND COALESCE(scheduled_at, created_at) >= :start
                              AND COALESCE(scheduled_at, created_at) < :end
                        )
                        """)
                .param("patient", patientId)
                .param("start", utc(day.atStartOfDay(zone).toInstant()))
                .param("end", utc(day.plusDays(1).atStartOfDay(zone).toInstant()))
                .query(Boolean.class)
                .single();
    }

    private EncounterRegistrationResponse insertEncounter(
            RegistrationCommand command,
            UUID encounterId,
            Instant scheduledAt
    ) {
        return jdbc.sql("""
                        INSERT INTO medical_encounters (
                            id, encounter_number, patient_id, doctor_id, scheduled_at, chief_complaint
                        )
                        VALUES (:id, :number, :patient, :doctor, :schedule, :complaint)
                        RETURNING id, encounter_number, patient_id, doctor_id, scheduled_at, created_at
                        """)
                .param("id", encounterId)
                .param("number", "OP-" + encounterId.toString().replace("-", ""))
                .param("patient", command.patientId())
                .param("doctor", command.doctorId())
                .param("schedule", utc(scheduledAt))
                .param("complaint", command.chiefComplaint())
                .query((row, index) -> header(row))
                .single();
    }

    private void recordCreation(
            RegistrationCommand command,
            Patient patient,
            EncounterRegistrationResponse encounter
    ) {
        histories.saveAndFlush(EncounterStatusHistory.builder()
                .encounter(encounters.getReferenceById(encounter.id()))
                .toStatus(EncounterStatus.SCHEDULED)
                .actorUserId(command.actorId())
                .occurredAt(encounter.createdAt())
                .build());

        audits.saveAndFlush(ClinicalAuditLog.builder()
                .patient(patient)
                .actorUserId(command.actorId())
                .actorType(AuditActorType.USER)
                .operation(AuditOperation.CREATE)
                .entityType("medical_encounters")
                .entityId(encounter.id())
                .occurredAt(encounter.createdAt())
                .build());
    }

    private static EncounterRegistrationResponse header(ResultSet row) throws SQLException {
        return new EncounterRegistrationResponse(
                row.getObject("id", UUID.class),
                row.getString("encounter_number"),
                row.getObject("patient_id", UUID.class),
                row.getObject("doctor_id", UUID.class),
                EncounterStatus.SCHEDULED,
                row.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                row.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
