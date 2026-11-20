package com.hospitalmanagement.hospital.encounter.clinical;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClinicalDtos {
    private ClinicalDtos() {
    }

    public record VitalMeasurements(
            @DecimalMin(value = "-273.15", inclusive = false) @Digits(integer = 3, fraction = 2)
            BigDecimal temperatureC,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal systolicBpMmhg,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal diastolicBpMmhg,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal heartRateBpm,
            @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal respiratoryRatePerMin,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal oxygenSaturationPct,
            @DecimalMin("0") @Digits(integer = 5, fraction = 3) BigDecimal weightKg,
            @DecimalMin("0") @Digits(integer = 5, fraction = 2) BigDecimal heightCm
    ) {
        boolean isEmpty() {
            return temperatureC == null && systolicBpMmhg == null && diastolicBpMmhg == null
                    && heartRateBpm == null && respiratoryRatePerMin == null
                    && oxygenSaturationPct == null && weightKg == null && heightCm == null;
        }
    }

    public record RecordVitals(
            Instant measuredAt,
            @Valid @NotNull
            VitalMeasurements measurements
    ) {
    }

    public record Vitals(
            UUID id, Instant measuredAt, Instant createdAt, VitalMeasurements measurements
    ) {
    }

    public record CreateNote(@NotBlank @Size(max = 10000) String content) {
    }

    public record EditNote(
            @NotBlank @Size(max = 10000) String content,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedRevision
    ) {
    }

    public record SignNote(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedRevision) {
    }

    public record Note(
            UUID id, UUID authorDoctorId, String type, String status, String content,
            Instant createdAt, Instant updatedAt, Instant signedAt, String revision
    ) {
    }

    public record NoteSummary(
            UUID id, UUID authorDoctorId, String type, String status,
            Instant createdAt, Instant updatedAt, Instant signedAt
    ) {
    }

    public record RecordDiagnosis(
            @NotNull
            UUID diagnosisCodeId,

            boolean primary,

            @Size(max = 2000)
            String clinicalDescription
    ) {
    }

    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {
    }
}
