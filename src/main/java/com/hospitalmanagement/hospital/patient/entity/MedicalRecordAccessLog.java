package com.hospitalmanagement.hospital.patient.entity;

import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOperation;
import com.hospitalmanagement.hospital.patient.enums.RecordAccessOutcome;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "medical_record_access_logs", schema = "public")
@FieldNameConstants(innerTypeName = "Fields", level = AccessLevel.PUBLIC)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalRecordAccessLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    Patient patient;

    @Column(name = "actor_user_id")
    UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    AuditActorType actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    RecordAccessOperation operation;

    @Column(name = "entity_type", length = 60)
    String entityType;

    @Column(name = "entity_id")
    UUID entityId;

    @Column(name = "purpose_code", nullable = false, length = 60)
    String purposeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    RecordAccessOutcome outcome;

    @Column(name = "correlation_id")
    UUID correlationId;

    // Loaded on read; PostgreSQL supplies the append-only event timestamp.
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    Instant occurredAt;

    @Builder
    public MedicalRecordAccessLog(
            Patient patient,
            UUID actorUserId,
            AuditActorType actorType,
            RecordAccessOperation operation,
            String entityType,
            UUID entityId,
            String purposeCode,
            RecordAccessOutcome outcome,
            UUID correlationId
    ) {
        this.patient = patient;
        this.actorUserId = actorUserId;
        this.actorType = actorType;
        this.operation = operation;
        this.entityType = entityType;
        this.entityId = entityId;
        this.purposeCode = purposeCode;
        this.outcome = outcome;
        this.correlationId = correlationId;
    }
}
