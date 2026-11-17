package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum SexAtBirth {
    FEMALE("female"),
    MALE("male"),
    INTERSEX("intersex"),
    UNKNOWN("unknown");

    @EnumeratedValue
    private final String value;

    SexAtBirth(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
