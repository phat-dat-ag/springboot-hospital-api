package com.hospitalmanagement.hospital.patient.entity;

import com.hospitalmanagement.hospital.patient.enums.AuditActorType;
import com.hospitalmanagement.hospital.patient.enums.AuditOperation;

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

/**
 * Append-only audit metadata; never include tokens or clinical payloads.
 */
@Entity
@Table(name = "clinical_audit_logs", schema = "public")
@FieldNameConstants(
        innerTypeName = "Fields",
        level = AccessLevel.PUBLIC
)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClinicalAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    Patient patient;

    @Column(name = "actor_user_id")
    UUID actorUserId;

    // SQL values: user, service, system.
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    AuditActorType actorType;

    // SQL values: create, update, sign, cancel, correct, supersede, soft_delete.
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 20)
    AuditOperation operation;

    @Column(name = "entity_type", nullable = false, length = 60)
    String entityType;

    // Polymorphic entity identifier; deliberately not a JPA association.
    @Column(name = "entity_id", nullable = false)
    UUID entityId;

    @Column(name = "reason_code", length = 60)
    String reasonCode;

    @Column(name = "correlation_id")
    UUID correlationId;

    // PostgreSQL generates this value; it is loaded when the immutable event is read.
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    Instant occurredAt;

    @Builder
    public ClinicalAuditLog(
            Patient patient,
            UUID actorUserId,
            AuditActorType actorType,
            AuditOperation operation,
            String entityType,
            UUID entityId,
            String reasonCode,
            UUID correlationId
    ) {
        this.patient = patient;
        this.actorUserId = actorUserId;
        this.actorType = actorType;
        this.operation = operation;
        this.entityType = entityType;
        this.entityId = entityId;
        this.reasonCode = reasonCode;
        this.correlationId = correlationId;
    }
}
