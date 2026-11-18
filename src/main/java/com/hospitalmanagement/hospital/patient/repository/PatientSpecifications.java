package com.hospitalmanagement.hospital.patient.repository;

import com.hospitalmanagement.hospital.patient.dto.request.PatientSearchRequest;
import com.hospitalmanagement.hospital.patient.entity.Patient;

import lombok.NonNull;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PatientSpecifications {
    private PatientSpecifications() {
    }

    public static Specification<@NonNull Patient> matching(PatientSearchRequest request) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(builder.isNull(root.get(Patient.Fields.deletedAt)));

            if (request.medicalRecordNumber() != null) {
                predicates.add(builder.equal(
                        root.get(Patient.Fields.medicalRecordNumber),
                        request.medicalRecordNumber()
                ));
            }

            if (request.name() != null) {
                String pattern = "%" + escapeLike(request.name().toLowerCase(Locale.ROOT)) + "%";

                predicates.add(builder.or(
                        builder.like(
                                builder.lower(root.get(Patient.Fields.givenName)), pattern, '!'
                        ),
                        builder.like(
                                builder.lower(root.get(Patient.Fields.familyName)), pattern, '!'
                        )
                ));
            }

            if (request.dateOfBirth() != null) {
                predicates.add(builder.equal(
                        root.get(Patient.Fields.dateOfBirth),
                        request.dateOfBirth()
                ));
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
