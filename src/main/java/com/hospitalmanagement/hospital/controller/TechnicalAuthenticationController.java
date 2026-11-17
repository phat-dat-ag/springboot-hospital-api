package com.hospitalmanagement.hospital.controller;

import com.hospitalmanagement.hospital.common.controller.BaseController;
import com.hospitalmanagement.hospital.common.response.APIResponse;
import com.hospitalmanagement.hospital.config.OpenApiConfig;
import com.hospitalmanagement.hospital.dto.response.AuthenticatedCallerResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hospital/technical")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TechnicalAuthenticationController extends BaseController {
    @GetMapping("/authentication")
    public ResponseEntity<@NonNull APIResponse<AuthenticatedCallerResponse>> authentication(
            JwtAuthenticationToken authentication
    ) {
        return success(new AuthenticatedCallerResponse(
                authentication.getToken().getSubject(),
                authentication.getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList(),
                authentication.getToken().getClaimAsString("iss")
        ));
    }
}
