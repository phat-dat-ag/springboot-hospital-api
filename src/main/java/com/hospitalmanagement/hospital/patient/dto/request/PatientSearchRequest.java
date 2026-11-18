package com.hospitalmanagement.hospital.patient.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PatientSearchRequest(
        @Size(min = 1, max = 40, message = "Medical record number must contain 1 to 40 characters")
        @Schema(description = "Exact, case-sensitive medical record number")
        String medicalRecordNumber,

        @Size(min = 2, max = 100, message = "Name must contain 2 to 100 characters")
        @Schema(description = "Case-insensitive literal substring of given or family name")
        String name,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate dateOfBirth,

        @Min(value = 0, message = "Page must not be negative")
        @Schema(defaultValue = "0", minimum = "0")
        Integer page,

        @Min(value = 1, message = "Size must be at least 1")
        @Max(value = 100, message = "Size must not exceed 100")
        @Schema(defaultValue = "20", minimum = "1", maximum = "100")
        Integer size,

        @Pattern(
                regexp = "(createdAt|medicalRecordNumber),(asc|desc)",
                message = "Sort must be createdAt or medicalRecordNumber, followed by asc or desc"
        )
        @Schema(
                defaultValue = "createdAt,desc",
                allowableValues = {
                        "createdAt,asc", "createdAt,desc",
                        "medicalRecordNumber,asc", "medicalRecordNumber,desc"
                }
        )
        String sort
) {
    public PatientSearchRequest {
        medicalRecordNumber = medicalRecordNumber == null ? null : medicalRecordNumber.trim();
        name = name == null ? null : name.strip();
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "createdAt,desc" : sort;
    }
}
