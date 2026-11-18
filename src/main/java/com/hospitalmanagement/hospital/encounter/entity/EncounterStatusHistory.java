package com.hospitalmanagement.hospital.encounter.entity;

import com.hospitalmanagement.hospital.encounter.enums.EncounterStatus;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
@Table(name = "encounter_status_histories", schema = "public")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EncounterStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false, updatable = false)
    private MedicalEncounter encounter;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private EncounterStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private EncounterStatus toStatus;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Builder
    public EncounterStatusHistory(
            MedicalEncounter encounter,
            EncounterStatus fromStatus,
            EncounterStatus toStatus,
            UUID actorUserId,
            String reason,
            Instant occurredAt
    ) {
        this.encounter = encounter;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
}

