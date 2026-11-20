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
    CLINICAL_NOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Clinical note not found"),
    DIAGNOSIS_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "Active diagnosis code not found"),
    ENCOUNTER_DIAGNOSIS_CONFLICT(HttpStatus.CONFLICT, "Active diagnosis code or primary already exists"),
    CLINICAL_NOTE_CONFLICT(HttpStatus.CONFLICT, "The note is no longer the requested draft"),
    CLINICAL_ENCOUNTER_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "Examination is not in progress"),
    CLINICAL_DOCUMENTATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Clinical documentation could not be recorded"),
    ENCOUNTER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Encounter action is not authorized"),
    ENCOUNTER_STATUS_CONFLICT(HttpStatus.CONFLICT, "Encounter status has changed; refresh the visit"),
    ENCOUNTER_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "Encounter transition is not allowed"),
    ENCOUNTER_PATIENT_INACTIVE(HttpStatus.CONFLICT, "Encounter patient is inactive"),
    ENCOUNTER_DOCTOR_UNAVAILABLE(HttpStatus.CONFLICT, "Assigned doctor is unavailable or expired"),
    ENCOUNTER_START_REQUIRED(HttpStatus.CONFLICT, "Encounter requires a valid start timestamp"),
    ENCOUNTER_SIGNED_ASSESSMENT_REQUIRED(HttpStatus.CONFLICT, "A signed assessment is required"),
    ENCOUNTER_PRIMARY_DIAGNOSIS_REQUIRED(HttpStatus.CONFLICT, "Exactly one active primary diagnosis is required"),
    ENCOUNTER_CONCLUSION_REQUIRED(HttpStatus.CONFLICT, "A clinical conclusion is required"),
    ENCOUNTER_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Cancellation requires a nonblank reason"),
    ENCOUNTER_LOCK_CONFLICT(HttpStatus.CONFLICT, "Encounter is busy; refresh before trying again"),
    ENCOUNTER_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key already used"),
    ENCOUNTER_OPEN_VISIT(HttpStatus.CONFLICT, "An open visit exists for that clinic day"),
    ENCOUNTER_SCHEDULE_INVALID(HttpStatus.BAD_REQUEST, "Schedule is outside the supported clinic dates"),

    DOCTOR_ACCOUNT_NOT_FOUND(HttpStatus.CONFLICT, "Linked doctor account does not exist"),
    DOCTOR_ACCOUNT_INACTIVE(HttpStatus.CONFLICT, "Linked doctor account is inactive"),
    DOCTOR_ACCOUNT_ROLE_INVALID(HttpStatus.CONFLICT, "Linked account does not have the doctor role"),
    DOCTOR_ACCOUNT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Doctor eligibility service unavailable"),
    DOCTOR_ACCOUNT_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "Doctor eligibility response is invalid"),
    ENCOUNTER_REGISTRATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to register encounter"),

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
