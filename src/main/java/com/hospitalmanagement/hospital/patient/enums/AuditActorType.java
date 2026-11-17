package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum AuditActorType {
    USER("user"),
    SERVICE("service"),
    SYSTEM("system");

    @EnumeratedValue
    private final String value;

    AuditActorType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

