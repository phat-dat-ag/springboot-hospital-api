package com.hospitalmanagement.hospital.patient.entity;

import com.hospitalmanagement.hospital.common.entity.TimestampedEntity;
import com.hospitalmanagement.hospital.patient.enums.ContactType;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_contacts", schema = "public")
@FieldNameConstants(
        innerTypeName = "Fields",
        level = AccessLevel.PUBLIC
)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Getter
@Setter
@NoArgsConstructor
public class PatientContact extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    Patient patient;

    // SQL values: self, emergency, guardian.
    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 20)
    ContactType contactType;

    @Column(name = "contact_name", length = 200)
    String contactName;

    @Column(name = "relationship", length = 100)
    String relationship;

    @Column(name = "phone", length = 40)
    String phone;

    @Column(name = "email", length = 254)
    String email;

    @Column(name = "address_text", columnDefinition = "text")
    String addressText;

    @Column(name = "is_primary", nullable = false)
    boolean primary;

    @Column(name = "deleted_at")
    Instant deletedAt;
}
