package com.hospitalmanagement.hospital.common.controller;

import com.hospitalmanagement.hospital.common.response.APIResponse;
import lombok.NonNull;
import org.springframework.http.ResponseEntity;

public abstract class BaseController {

    protected <T> ResponseEntity<@NonNull APIResponse<T>> success(T data) {
        return ResponseEntity.ok(
                APIResponse.success(null, null, data)
        );
    }
}