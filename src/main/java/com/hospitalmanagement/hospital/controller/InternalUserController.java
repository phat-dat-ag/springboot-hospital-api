package com.hospitalmanagement.hospital.controller;

import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.dto.response.UserIntegrationResponse;
import com.hospitalmanagement.hospital.service.UserIntegrationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospital/technical")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@Slf4j
public class InternalUserController {

    private final UserIntegrationService userIntegrationService;

    @GetMapping("/users/{id}")
    public UserIntegrationResponse getUser(@PathVariable UUID id) {
        log.info("Get user by id requested");

        UserIntegrationResponse response = userIntegrationService.getUser(id);

        log.info(
                "Got user successfully: id={}, name={}, email={}",
                response.user().id(),
                response.user().username(),
                response.user().email()
        );

        return response;
    }
}
