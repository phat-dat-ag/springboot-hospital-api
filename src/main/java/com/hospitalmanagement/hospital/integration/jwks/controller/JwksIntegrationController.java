package com.hospitalmanagement.hospital.integration.jwks.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.integration.jwks.dto.PublicKeysResponse;
import com.hospitalmanagement.hospital.integration.jwks.service.JwksIntegrationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hospital/technical/jwks")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
@Slf4j
public class JwksIntegrationController extends BaseController {

    private final JwksIntegrationService jwksIntegrationService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<@NonNull APIResponse<PublicKeysResponse>> getPublicKeys() {
        log.info("Public key lookup requested");

        PublicKeysResponse publicKeys = jwksIntegrationService.getPublicKeys();

        log.atInfo()
                .addKeyValue("key_count", publicKeys.keys().size())
                .log("Public key lookup completed");

        return success(publicKeys);
    }
}
