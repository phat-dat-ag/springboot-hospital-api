package com.hospitalmanagement.hospital.encounter.entity;

import com.hospitalmanagement.hospital.common.entity.TimestampedEntity;
import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;
import com.hospitalmanagement.hospital.patient.entity.Patient;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Existing encounters are provisioned outside this bounded status workflow. */
@Entity
@Table(name = "medical_encounters", schema = "public")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalEncounter extends TimestampedEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", updatable = false)
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EncounterStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    public void changeStatus(EncounterStatus target, String reason, Instant instant) {
        status = target;

        if (target == EncounterStatus.IN_PROGRESS) {
            startedAt = instant;
        }

        if (target == EncounterStatus.COMPLETED) {
            endedAt = instant;
        }

        if (target == EncounterStatus.CANCELLED) {
            cancellationReason = reason;
        }
    }
}

