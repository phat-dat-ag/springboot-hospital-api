package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum RecordAccessOutcome {
    ALLOWED("allowed"),
    DENIED("denied"),
    FAILED("failed");

    @EnumeratedValue
    private final String value;

    RecordAccessOutcome(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

