package com.hospitalmanagement.hospital.security.config;

import com.hospitalmanagement.hospital.security.jwt.user.JwtRoleConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;

/**
 * Authenticates incoming user tokens; outbound service signing is configured separately.
 */
@Configuration("securityConfig")
public class UserSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtRoleConverter roles
    ) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(roles);

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        http.oauth2ResourceServer(server -> server
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .withObjectPostProcessor(
                        new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(
                                    O filter
                            ) {
                                var entryPoint = new BearerTokenAuthenticationEntryPoint();
                                var failureHandler =
                                        new AuthenticationEntryPointFailureHandler(entryPoint);

                                // Return 401 when key retrieval fails.
                                failureHandler.setRethrowAuthenticationServiceException(false);
                                filter.setAuthenticationFailureHandler(failureHandler);

                                return filter;
                            }
                        }
                )
        );

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**"
                ).permitAll()

                .requestMatchers(
                        HttpMethod.GET,
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**"
                ).permitAll()

                .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/hospital/patients",
                        "/api/v1/hospital/patients/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/hospital/patients",
                        "/api/v1/hospital/patients/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/hospital/patients/*",
                        "/api/v1/hospital/patients/*/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.HEAD,
                        "/api/v1/hospital/patients",
                        "/api/v1/hospital/patients/",
                        "/api/v1/hospital/patients/*",
                        "/api/v1/hospital/patients/*/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/hospital/doctors",
                        "/api/v1/hospital/doctors/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.HEAD,
                        "/api/v1/hospital/doctors",
                        "/api/v1/hospital/doctors/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/hospital/encounters",
                        "/api/v1/hospital/encounters/"
                ).hasRole("STAFF")

                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/v1/hospital/encounters/*/status",
                        "/api/v1/hospital/encounters/*/status/"
                ).hasRole("ADMIN")

                .requestMatchers(
                        "/api/v1/hospital/**"
                ).hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated());
        return http.build();
    }
}
