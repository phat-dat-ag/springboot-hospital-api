package com.hospitalmanagement.hospital.patient.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.EnumeratedValue;

public enum RecordAccessOperation {
    VIEW("view"),
    SEARCH("search"),
    DOWNLOAD("download"),
    EXPORT("export");

    @EnumeratedValue
    private final String value;

    RecordAccessOperation(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

