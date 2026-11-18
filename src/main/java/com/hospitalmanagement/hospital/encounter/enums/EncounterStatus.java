package com.hospitalmanagement.hospital.encounter.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum EncounterStatus {
    SCHEDULED("scheduled"),
    ARRIVED("arrived"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    ENTERED_IN_ERROR("entered_in_error");

    @EnumeratedValue
    private final String value;

    EncounterStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

