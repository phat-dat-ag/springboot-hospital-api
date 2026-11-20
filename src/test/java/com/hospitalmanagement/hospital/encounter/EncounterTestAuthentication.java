package com.hospitalmanagement.hospital.encounter;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;

final class EncounterTestAuthentication {
    private EncounterTestAuthentication() {
    }

    static JwtAuthenticationToken authentication(String actor, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(actor)
                .build();

        return new JwtAuthenticationToken(jwt, createAuthorityList(role));
    }
}
