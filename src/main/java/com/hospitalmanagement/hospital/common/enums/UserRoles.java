package com.hospitalmanagement.hospital.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.EnumeratedValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum UserRoles {
    USER(1),
    PATIENT(2),
    STAFF(3),
    DOCTOR(4),
    ADMIN(5);

    @EnumeratedValue
    private final int value;

    UserRoles(int value) {
        this.value = value;
    }

    @JsonValue
    public int getValue() {
        return value;
    }

    public static final Set<Integer> ROLE_VALUES = Arrays.stream(values())
            .map(UserRoles::getValue)
            .collect(Collectors.toUnmodifiableSet());
}
