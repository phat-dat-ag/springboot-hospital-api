package com.hospitalmanagement.hospital.client;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.config.ServiceEndpointProperties;
import com.hospitalmanagement.hospital.dto.InternalUserDTO;
import com.hospitalmanagement.hospital.dto.response.InternalUserResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@Slf4j
public class UserServiceClient {

    private final RestClient restClient;
    private final ServiceEndpointProperties properties;

    public UserServiceClient(
            @Qualifier("userRestClient")
            RestClient restClient,

            ServiceEndpointProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public InternalUserResponse getUser(UUID uuid) {
        UserAccount user = lookup(uuid);

        return new InternalUserResponse(new InternalUserDTO(
                user.id(),
                user.username(),
                user.email(),
                Boolean.TRUE.equals(user.isActivated()),
                user.createdAt(),
                user.updatedAt()
        ));
    }

    public AccountIdentity getAccountIdentity(UUID uuid) {
        UserAccount user = lookup(uuid);
        JsonNode role = user.role();

        if (role == null
                || !role.isIntegralNumber()
                || !role.canConvertToInt()
                || role.intValue() < 1
                || role.intValue() > 5
                || user.isActivated() == null
        ) {
            log.atWarn()
                    .addKeyValue("event", "dependency.user.invalid_identity")
                    .log("User Service returned incomplete or invalid account identity");

            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return new AccountIdentity(user.id(), user.isActivated(), role.intValue());
    }

    private UserAccount lookup(UUID uuid) {
        UserLookupResponse response;

        try {
            response = restClient
                    .get()
                    .uri(properties.getUser().getUser() + "/{id}", uuid)
                    .retrieve()
                    .body(UserLookupResponse.class);
        } catch (RestClientResponseException ex) {
            // Preserve the existing error contract without logging upstream bodies or user data.
            log.atWarn()
                    .addKeyValue("event", "dependency.user.failed")
                    .addKeyValue("upstream.status", ex.getStatusCode().value())
                    .log("User Service request failed");

            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        } catch (RestClientException ex) {
            log.atWarn()
                    .addKeyValue("event", "dependency.user.failed")
                    .addKeyValue(
                            "upstream.errorType",
                            ex.getClass().getSimpleName()
                    ).log("User Service request failed");

            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (response == null
                || response.user() == null
                || !uuid.equals(response.user().id())
        ) {
            log.atWarn()
                    .addKeyValue("event", "dependency.user.invalid_response")
                    .log("User Service returned an invalid user response");

            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return response.user();
    }

    public record AccountIdentity(UUID id, boolean isActivated, int role) {
    }

    private record UserLookupResponse(UserAccount user) {
    }

    private record UserAccount(
            UUID id,
            String username,
            String email,
            Boolean isActivated,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            JsonNode role
    ) {
    }
}
