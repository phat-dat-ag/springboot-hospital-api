package com.hospitalmanagement.hospital.encounter.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read subset used by the encounter workflow; no doctor-management writes.
 */
@Entity
@Table(name = "doctors", schema = "public")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Doctor {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "external_user_id")
    private UUID externalUserId;

    @Column(name = "license_expires_on")
    private LocalDate licenseExpiresOn;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}

