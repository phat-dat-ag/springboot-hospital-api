package com.hospitalmanagement.hospital.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Client errors
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_ENUM_VALUE(HttpStatus.BAD_REQUEST, "Request contains an invalid enum value"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed"),

    PATIENT_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "Medical record number is already registered"
    ),
    CONTACT_DETAILS_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "Each contact requires a nonblank phone, email or address"
    ),
    CONTACT_NAME_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "Emergency and guardian contacts require a contact name"
    ),
    DUPLICATE_PRIMARY_CONTACT(
            HttpStatus.BAD_REQUEST,
            "Only one primary contact is allowed per contact type"
    ),
    INVALID_ACTOR_ID(
            HttpStatus.UNAUTHORIZED,
            "Authenticated user identity is invalid"
    ),
    PATIENT_SEARCH_FILTER_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "At least one patient search filter is required"
    ),
    PATIENT_SEARCH_PAGE_OUT_OF_RANGE(
            HttpStatus.BAD_REQUEST,
            "Page offset exceeds the supported range"
    ),

    ENCOUNTER_NOT_FOUND(HttpStatus.NOT_FOUND, "Encounter not found"),
    ENCOUNTER_STATUS_CONFLICT(HttpStatus.CONFLICT, "Encounter status has changed; refresh the visit"),
    ENCOUNTER_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "Encounter transition is not allowed"),
    ENCOUNTER_PATIENT_INACTIVE(HttpStatus.CONFLICT, "Encounter patient is inactive"),
    ENCOUNTER_DOCTOR_UNAVAILABLE(HttpStatus.CONFLICT, "Assigned doctor is unavailable or expired"),
    ENCOUNTER_START_REQUIRED(HttpStatus.CONFLICT, "Encounter requires a valid start timestamp"),
    ENCOUNTER_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Cancellation requires a nonblank reason"),
    ENCOUNTER_LOCK_CONFLICT(HttpStatus.CONFLICT, "Encounter is busy; refresh before trying again"),

    // Server errors
    ENCOUNTER_CHANGE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Unable to record encounter status change"
    ),
    PATIENT_ACCESS_LOG_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Unable to record patient access"
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
    );

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
