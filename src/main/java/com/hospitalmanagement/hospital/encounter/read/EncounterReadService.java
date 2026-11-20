package com.hospitalmanagement.hospital.encounter.read;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.service.EncounterAccess;
import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOutcome;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EncounterReadService {
    private final EncounterReadRepository encounters;
    private final EncounterAccess access;
    private final MedicalRecordAccessLogRepository audit;
    private final PatientRepository patients;
    private final Clock clinicClock;

    @Transactional
    public Page queue(
            LocalDate requestedDate,
            String status,
            int page,
            int size,
            boolean assigned,
            JwtAuthenticationToken authentication
    ) {
        UUID actor = EncounterAccess.actorId(authentication);
        EncounterAccess.requireRole(authentication, assigned ? "ROLE_DOCTOR" : "ROLE_STAFF");
        LocalDate date = requestedDate == null ? LocalDate.now(clinicClock) : requestedDate;

        if (date.getYear() < 1 || date.getYear() > 9999 || page < 0 || size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        List<String> statuses = statuses(status, assigned);
        UUID doctor = assigned ? access.requireActiveDoctor(actor) : null;
        var zone = clinicClock.getZone();
        var rows = encounters.queue(
                date.atStartOfDay(zone).toOffsetDateTime(),
                date.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                statuses, (long) page * size, size + 1, doctor, actor
        );

        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        recordAccess(items, actor, RecordAccessOperation.SEARCH,
                assigned ? "assigned_encounter_queue" : "reception_encounter_queue");

        return new Page(date, page, size, rows.size() > size, items);
    }

    @Transactional
    public EncounterView details(UUID id, JwtAuthenticationToken authentication) {
        UUID actor = EncounterAccess.actorId(authentication);
        UUID doctor = null;

        if (!EncounterAccess.hasRole(authentication, "ROLE_STAFF")) {
            EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");
            doctor = access.requireActiveDoctor(actor);
        }

        var encounter = encounters.find(id, doctor, actor)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENCOUNTER_NOT_FOUND));
        recordAccess(List.of(encounter), actor, RecordAccessOperation.VIEW, "encounter_workflow_view");

        return encounter;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PatientHistory visits(
            UUID patient,
            String cursor,
            int size,
            JwtAuthenticationToken authentication
    ) {
        UUID actor = EncounterAccess.actorId(authentication);
        EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");
        HistoryCursor boundary = pageBoundary(cursor, size);
        UUID doctor = access.requireActiveDoctor(actor);

        var header = encounters.patientWithCompletedVisit(patient, doctor, actor)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var rows = encounters.visits(patient, doctor, actor, boundary, size + 1);
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        var events = new ArrayList<MedicalRecordAccessLog>();

        UUID correlation = UUID.randomUUID();
        events.add(accessEvent(
                patient,
                patient,
                actor,
                RecordAccessOperation.SEARCH,
                "patients",
                "patient_visit_history",
                correlation
        ));

        items.forEach(visit -> events.add(accessEvent(
                patient,
                visit.encounter().id(),
                actor,
                RecordAccessOperation.SEARCH,
                "medical_encounters",
                "patient_visit_history",
                correlation
        )));

        saveAccess(events);

        String next = null;
        if (rows.size() > size) {
            var last = items.get(size - 1);

            next = new HistoryCursor(
                    last.createdAt(),
                    last.encounter().id()
            ).encode();
        }

        return new PatientHistory(header, items, next);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public StatusHistory statusHistory(
            UUID id,
            String cursor,
            int size,
            JwtAuthenticationToken authentication
    ) {
        UUID actor = EncounterAccess.actorId(authentication);
        EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");
        HistoryCursor boundary = pageBoundary(cursor, size);
        UUID doctor = access.requireActiveDoctor(actor);

        var encounter = encounters.find(id, doctor, actor)
                .orElseThrow(() -> new BusinessException(
                                ErrorCode.ENCOUNTER_NOT_FOUND
                        )
                );
        var rows = encounters.statusHistory(id, boundary, size + 1);
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        recordAccess(
                List.of(encounter),
                actor,
                RecordAccessOperation.VIEW,
                "encounter_status_history"
        );

        String next = null;
        if (rows.size() > size) {
            var last = items.get(size - 1);
            next = new HistoryCursor(last.occurredAt(), last.id()).encode();
        }

        return new StatusHistory(items, next);
    }

    private static HistoryCursor pageBoundary(String cursor, int size) {
        if (size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return HistoryCursor.parse(cursor);
    }

    private static List<String> statuses(String status, boolean assigned) {
        if (status == null) {
            return assigned ? List.of("scheduled", "arrived", "in_progress")
                    : List.of("scheduled", "arrived");
        }

        return Arrays.stream(EncounterStatus.values())
                .filter(value -> value.getValue().equals(status))
                .findFirst()
                .map(value -> List.of(value.getValue()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    }

    private void recordAccess(
            List<EncounterView> encounters,
            UUID actor,
            RecordAccessOperation operation,
            String purpose
    ) {
        if (encounters.isEmpty()) {
            return;
        }

        UUID correlation = UUID.randomUUID();

        saveAccess(encounters.stream()
                .map(encounter -> accessEvent(
                        encounter.patientId(),
                        encounter.id(),
                        actor,
                        operation,
                        "medical_encounters",
                        purpose,
                        correlation
                ))
                .toList());
    }

    private MedicalRecordAccessLog accessEvent(
            UUID patient,
            UUID id,
            UUID actor,
            RecordAccessOperation operation,
            String entity,
            String purpose,
            UUID correlation
    ) {
        return MedicalRecordAccessLog.builder()
                .patient(patients.getReferenceById(patient))
                .actorUserId(actor)
                .actorType(AuditActorType.USER)
                .operation(operation)
                .entityType(entity)
                .entityId(id)
                .purposeCode(purpose)
                .outcome(RecordAccessOutcome.ALLOWED)
                .correlationId(correlation)
                .build();
    }

    private void saveAccess(List<MedicalRecordAccessLog> events) {
        try {
            audit.saveAllAndFlush(events);
        } catch (DataAccessException failure) {
            log.atError()
                    .addKeyValue("event", "encounter.access_log.failed")
                    .log("Encounter access audit could not be recorded");

            throw new BusinessException(ErrorCode.PATIENT_ACCESS_LOG_FAILED);
        }
    }

    public record PatientHistory(
            EncounterReadRepository.PatientHeader patient,
            List<EncounterReadRepository.Visit> items,
            String nextCursor
    ) {
    }

    public record StatusHistory(
            List<EncounterReadRepository.StatusEvent> items,
            String nextCursor
    ) {
    }

    public record Page(
            LocalDate date,
            int page,
            int size,
            boolean hasNext,
            List<EncounterView> items
    ) {
    }
}
