package com.hospitalmanagement.hospital.patient.entity;

import com.hospitalmanagement.hospital.common.entity.TimestampedEntity;
import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients", schema = "public")
@FieldNameConstants(
        innerTypeName = "Fields",
        level = AccessLevel.PUBLIC
)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Getter
@Setter
@NoArgsConstructor
public class Patient extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    UUID id;

    @Column(name = "medical_record_number", nullable = false, unique = true, length = 40)
    String medicalRecordNumber;

    // An external User Service identity, not a cross-service JPA association.
    @Column(name = "external_user_id", unique = true)
    UUID externalUserId;

    @Column(name = "given_name", nullable = false, length = 100)
    String givenName;

    @Column(name = "family_name", length = 100)
    String familyName;

    @Column(name = "date_of_birth")
    LocalDate dateOfBirth;

    // SQL values: female, male, intersex, unknown.
    @Enumerated(EnumType.STRING)
    @Column(name = "sex_at_birth", length = 20)
    SexAtBirth sexAtBirth;

    @Column(name = "deceased_on")
    LocalDate deceasedOn;

    // Soft deletion is explicit; queries must filter out deleted records.
    @Column(name = "deleted_at")
    Instant deletedAt;
}
