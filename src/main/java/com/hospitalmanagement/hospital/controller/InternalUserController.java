package com.hospitalmanagement.hospital.controller;

import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.dto.response.InternalUserResponse;
import com.hospitalmanagement.hospital.service.impl.InternalUserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/technical")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class InternalUserController {

    private final InternalUserService internalUserService;

    @GetMapping("/users/{id}")
    public InternalUserResponse getUser(@PathVariable UUID id) {
        return internalUserService.getUser(id);
    }
}
