package com.hospitalmanagement.hospital.encounter.clinical;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.CreateNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.EditNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Note;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.NoteSummary;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Page;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.RecordVitals;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.SignNote;
import com.hospitalmanagement.hospital.encounter.clinical.ClinicalDtos.Vitals;
import com.hospitalmanagement.hospital.encounter.doctor.DoctorEligibility;
import com.hospitalmanagement.hospital.encounter.entity.Doctor;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.encounter.repository.DoctorRepository;
import com.hospitalmanagement.hospital.encounter.repository.MedicalEncounterRepository;
import com.hospitalmanagement.hospital.encounter.service.EncounterAccess;
import com.hospitalmanagement.hospital.patient.entity.ClinicalAuditLog;
import com.hospitalmanagement.hospital.patient.entity.MedicalRecordAccessLog;
import com.hospitalmanagement.hospital.patient.entity.Patient;
import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOutcome;
import com.hospitalmanagement.hospital.patient.repository.ClinicalAuditLogRepository;
import com.hospitalmanagement.hospital.patient.repository.MedicalRecordAccessLogRepository;
import com.hospitalmanagement.hospital.patient.repository.PatientRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class ClinicalDocumentationService {
    private static final String NOTE_ENTITY = "clinical_notes";
    private static final String VITAL_ENTITY = "vital_signs";
    private static final int MAX_PAGE_SIZE = 50;
    private static final Instant EARLIEST_MEASUREMENT = Instant.parse("0001-01-01T00:00:00Z");

    private final EncounterAccess access;
    private final DoctorEligibility eligibility;
    private final MedicalEncounterRepository encounters;
    private final DoctorRepository doctors;
    private final PatientRepository patients;
    private final ClinicalDocumentationRepository documentation;
    private final DiagnosisRepository diagnoses;
    private final ClinicalAuditLogRepository writes;
    private final MedicalRecordAccessLogRepository reads;
    private final JdbcClient jdbc;
    private final Clock clinicClock;

    @Transactional
    public Vitals recordVitals(
            UUID id,
            @Valid @NotNull RecordVitals request,
            JwtAuthenticationToken authentication
    ) {
        Context scope = scope(id, authentication, true);

        Instant now = clinicClock.instant();
        Instant measured = request.measuredAt() == null ? now : request.measuredAt();

        if (request.measurements().isEmpty()
                || measured.isBefore(EARLIEST_MEASUREMENT)
                || measured.isAfter(now)
        ) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        Vitals vital = documentation.insertVitals(id, scope.actor(), measured, request.measurements());

        writeAudit(scope, AuditOperation.CREATE, VITAL_ENTITY, vital.id(), vital.createdAt());

        return vital;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page<Vitals> vitals(UUID id, int page, int size, JwtAuthenticationToken authentication) {
        long offset = offset(page, size);

        Context scope = scope(id, authentication, false);

        var rows = documentation.vitals(id, offset, size + 1);

        readAudit(scope, RecordAccessOperation.SEARCH, "medical_encounters", id, "encounter_vitals");

        return page(rows, page, size);
    }

    @Transactional
    public Note create(
            UUID id,
            @Valid @NotNull CreateNote request,
            JwtAuthenticationToken authentication
    ) {
        Context scope = scope(id, authentication, true);

        Note note = documentation.insertNote(id, scope.doctor(), safeContent(request.content()));

        writeAudit(scope, AuditOperation.CREATE, NOTE_ENTITY, note.id(), note.createdAt());

        return note;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page<NoteSummary> notes(
            UUID id,
            int page,
            int size,
            JwtAuthenticationToken authentication
    ) {
        long offset = offset(page, size);

        Context scope = scope(id, authentication, false);

        var rows = documentation.notes(
                id,
                scope.doctor(),
                scope.allowDrafts(),
                offset, size + 1
        );

        readAudit(scope, RecordAccessOperation.SEARCH, "medical_encounters", id, "encounter_notes");

        return page(rows, page, size);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Note note(UUID id, UUID noteId, JwtAuthenticationToken authentication) {
        Context scope = scope(id, authentication, false);

        Note note = documentation.note(
                        id, noteId,
                        scope.doctor(),
                        scope.allowDrafts(),
                        false
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CLINICAL_NOTE_NOT_FOUND
                ));

        readAudit(scope, RecordAccessOperation.VIEW, NOTE_ENTITY, noteId, "encounter_note");

        return note;
    }

    @Transactional
    public Note edit(
            UUID id,
            UUID noteId,
            @Valid @NotNull EditNote request,
            JwtAuthenticationToken authentication
    ) {
        Context scope = scope(id, authentication, true);

        requireDraft(scope, id, noteId, request.expectedRevision());

        Note note = documentation.edit(id, noteId, safeContent(request.content()));

        writeAudit(scope, AuditOperation.UPDATE, NOTE_ENTITY, noteId, note.updatedAt());

        return note;
    }

    @Transactional
    public Note sign(
            UUID id,
            UUID noteId,
            @Valid @NotNull SignNote request,
            JwtAuthenticationToken authentication
    ) {
        Context scope = scope(id, authentication, true);

        requireDraft(scope, id, noteId, request.expectedRevision());

        Note note = documentation.sign(id, noteId);

        writeAudit(scope, AuditOperation.SIGN, NOTE_ENTITY, noteId, note.signedAt());

        return note;
    }

    @Transactional
    public ClinicalDtos.Conclusion updateConclusion(
            UUID id,
            @Valid @NotNull ClinicalDtos.UpdateConclusion request,
            JwtAuthenticationToken authentication
    ) {
        String conclusion = safeContent(request.conclusion()).strip();

        if (conclusion.codePoints()
                .noneMatch(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point))
        ) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        Context scope = scope(id, authentication, true);

        try {
            Instant updatedAt = jdbc.sql("""
                            UPDATE medical_encounters SET clinical_conclusion = :conclusion
                            WHERE id = :id AND status = 'in_progress'
                            RETURNING updated_at
                            """)
                    .param("conclusion", conclusion)
                    .param("id", id)
                    .query(OffsetDateTime.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CLINICAL_ENCOUNTER_NOT_IN_PROGRESS))
                    .toInstant();

            writeAudit(scope, AuditOperation.UPDATE, "medical_encounters", id, updatedAt);

            return new ClinicalDtos.Conclusion(conclusion, updatedAt);
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.CLINICAL_DOCUMENTATION_FAILED);
        }
    }

    public Page<DiagnosisRepository.Code> diagnosisCodes(
            String query,
            int page,
            int size,
            JwtAuthenticationToken authentication
    ) {
        UUID actor = EncounterAccess.actorId(authentication);

        EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");

        if (query == null || query.length() > 100 || query.indexOf(0) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        long offset = offset(page, size);

        access.requireActiveDoctor(actor);

        return page(diagnoses.codes(query.strip(), offset, size + 1), page, size);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page<DiagnosisRepository.Diagnosis> diagnoses(
            UUID id,
            int page,
            int size,
            JwtAuthenticationToken authentication
    ) {
        long offset = offset(page, size);

        Context scope = scope(id, authentication, false);

        var rows = diagnoses.diagnoses(id, offset, size + 1);

        readAudit(
                scope,
                RecordAccessOperation.SEARCH,
                "medical_encounters",
                id,
                "encounter_diagnoses"
        );

        return page(rows, page, size);
    }

    @Transactional
    public DiagnosisRepository.Diagnosis recordDiagnosis(
            UUID id,

            @Valid
            @NotNull
            ClinicalDtos.RecordDiagnosis request,

            JwtAuthenticationToken authentication
    ) {
        Context scope = scope(id, authentication, true);

        String description = request.clinicalDescription() == null
                ? null
                : safeContent(request.clinicalDescription()).strip();
        try {
            var code = diagnoses.activeCodeForShare(request.diagnosisCodeId())
                    .orElseThrow(
                            () -> new BusinessException(
                                    ErrorCode.DIAGNOSIS_CODE_NOT_FOUND
                            )
                    );
            var diagnosis = diagnoses.insert(
                            id,
                            scope.doctor(),
                            code,
                            request.primary(),
                            description == null || description.isEmpty()
                                    ? null
                                    : description
                    )
                    .orElseThrow(
                            () -> new BusinessException(
                                    ErrorCode.ENCOUNTER_DIAGNOSIS_CONFLICT
                            )
                    );

            diagnoses.recordHistory(diagnosis, scope.actor());

            writeAudit(
                    scope,
                    AuditOperation.CREATE,
                    "encounter_diagnoses",
                    diagnosis.id(),
                    diagnosis.diagnosedAt()
            );

            return diagnosis;
        } catch (PessimisticLockingFailureException failure) {
            throw new BusinessException(ErrorCode.ENCOUNTER_LOCK_CONFLICT);
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.CLINICAL_DOCUMENTATION_FAILED);
        }
    }

    private void requireDraft(Context scope, UUID encounter, UUID noteId, String revision) {
        Note note = documentation.note(encounter, noteId, scope.doctor(), true, true)
                .filter(record -> record.authorDoctorId().equals(scope.doctor()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CLINICAL_NOTE_NOT_FOUND));

        if (!"draft".equals(note.status()) || !note.revision().equals(revision)) {
            throw new BusinessException(ErrorCode.CLINICAL_NOTE_CONFLICT);
        }
    }

    private Context scope(UUID id, JwtAuthenticationToken authentication, boolean write) {
        UUID actor = EncounterAccess.actorId(authentication);

        EncounterAccess.requireRole(authentication, "ROLE_DOCTOR");

        UUID doctor = access.requireActiveDoctor(actor);

        try {
            return scopedEncounter(id, actor, doctor, write);
        } catch (PessimisticLockingFailureException failure) {
            throw new BusinessException(ErrorCode.ENCOUNTER_LOCK_CONFLICT);
        }
    }

    private Context scopedEncounter(UUID id, UUID actor, UUID doctorId, boolean write) {
        if (write) {
            jdbc.sql("select set_config('lock_timeout', '3s', true)").query(String.class).single();
        }

        var encounter = (write ? encounters.findByIdForUpdate(id) : encounters.findById(id))
                .filter(item -> item.getDoctor() != null && doctorId.equals(item.getDoctor().getId()))
                .orElseThrow(ClinicalDocumentationService::notFound);
        UUID patientId = encounter.getPatient().getId();
        Patient patient = (write ? patients.findByIdForUpdate(patientId) : patients.findById(patientId))
                .filter(item -> item.getDeletedAt() == null)
                .orElseThrow(ClinicalDocumentationService::notFound);
        Doctor doctor = (write ? doctors.findByIdForUpdate(doctorId) : doctors.findById(doctorId))
                .filter(item -> item.getDeletedAt() == null && actor.equals(item.getExternalUserId()))
                .orElseThrow(ClinicalDocumentationService::notFound);

        if (write) {
            eligibility.requiresEligibleDoctorProfile(doctor, LocalDate.now(clinicClock));
            if (encounter.getStatus() != EncounterStatus.IN_PROGRESS) {
                throw new BusinessException(ErrorCode.CLINICAL_ENCOUNTER_NOT_IN_PROGRESS);
            }
        }

        return new Context(
                actor,
                doctorId,
                patient,
                encounter.getStatus() != EncounterStatus.COMPLETED
        );
    }

    private void writeAudit(
            Context scope,
            AuditOperation operation,
            String entity,
            UUID id, Instant occurredAt
    ) {
        try {
            writes.saveAndFlush(ClinicalAuditLog.builder()
                    .patient(scope.patient()).actorUserId(scope.actor()).actorType(AuditActorType.USER)
                    .operation(operation).entityType(entity).entityId(id)
                    .correlationId(UUID.randomUUID()).occurredAt(occurredAt)
                    .build());
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.CLINICAL_DOCUMENTATION_FAILED);
        }
    }

    private void readAudit(
            Context scope,
            RecordAccessOperation operation,
            String entity,
            UUID id, String purpose
    ) {
        try {
            reads.saveAndFlush(MedicalRecordAccessLog.builder()
                    .patient(scope.patient()).actorUserId(scope.actor()).actorType(AuditActorType.USER)
                    .operation(operation).entityType(entity).entityId(id).purposeCode(purpose)
                    .outcome(RecordAccessOutcome.ALLOWED).correlationId(UUID.randomUUID())
                    .build()
            );
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.PATIENT_ACCESS_LOG_FAILED);
        }
    }

    private static String safeContent(String content) {
        if (content.indexOf(0) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return content;
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return (long) page * size;
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(List.copyOf(rows.subList(0, Math.min(size, rows.size()))),
                page, size, rows.size() > size);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.ENCOUNTER_NOT_FOUND);
    }

    private record Context(
            UUID actor,
            UUID doctor,
            Patient patient,
            boolean allowDrafts
    ) {
    }
}
