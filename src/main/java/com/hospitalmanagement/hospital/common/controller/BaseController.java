package com.hospitalmanagement.hospital.common.controller;

import com.hospitalmanagement.hospital.common.response.APIResponse;

import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public abstract class BaseController {

    protected <T> ResponseEntity<@NonNull APIResponse<T>> success(T data) {
        return success(data, ResponseEntity.ok());
    }

    protected <T> ResponseEntity<@NonNull APIResponse<T>> success(
            T data,
            ResponseEntity.BodyBuilder response
    ) {
        return response.body(APIResponse.success(null, null, data));
    }

    protected <T> ResponseEntity<@NonNull APIResponse<T>> created(
            String code,
            String message,
            T data
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(APIResponse.success(code, message, data));
    }
}
