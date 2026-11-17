package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum AuditOperation {
    CREATE("create"),
    UPDATE("update"),
    SIGN("sign"),
    CANCEL("cancel"),
    CORRECT("correct"),
    SUPERSEDE("supersede"),
    SOFT_DELETE("soft_delete");

    @EnumeratedValue
    private final String value;

    AuditOperation(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
