package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum ContactType {
    SELF("self"),
    EMERGENCY("emergency"),
    GUARDIAN("guardian");

    @EnumeratedValue
    private final String value;

    ContactType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
