package com.hospitalmanagement.hospital.client;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import com.hospitalmanagement.hospital.config.ServiceEndpointProperties;
import com.hospitalmanagement.hospital.dto.UserIntegrationDTO;
import com.hospitalmanagement.hospital.dto.response.UserIntegrationResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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

    public UserIntegrationResponse getUser(UUID userId) {
        UserAccount user = lookupUser(userId, false);

        return new UserIntegrationResponse(new UserIntegrationDTO(
                user.id(),
                user.username(),
                user.email(),
                user.isActivated() != null
                        && user.isActivated().asBoolean(false),
                user.createdAt(),
                user.updatedAt()
        ));
    }

    public AccountIdentity getAccountIdentity(UUID userId) {
        UserAccount user = lookupUser(userId, true);
        JsonNode role = user.role();

        if (role == null
                || !role.isIntegralNumber()
                || !role.canConvertToInt()
                || role.intValue() < 1
                || role.intValue() > 5
                || user.isActivated() == null
                || !user.isActivated().isBoolean()
        ) {
            log.atWarn()
                    .addKeyValue("event", "dependency.user.invalid_identity")
                    .log("User Service returned incomplete or invalid account identity");

            throw new BusinessException(ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE);
        }

        return new AccountIdentity(user.id(), user.isActivated().asBoolean(), role.intValue());
    }

    private UserAccount lookupUser(UUID userId, boolean forEligibility) {
        UserLookupResponse response;

        try {
            response = restClient
                    .get()
                    .uri(properties.getUser().getUser() + "/{id}", userId)
                    .retrieve()
                    .body(UserLookupResponse.class);
        } catch (RestClientResponseException restClientResponseException) {
            log.warn(
                    "User Service request failed, RestClientResponseException={}",
                    restClientResponseException.getMessage()
            );

            throw new BusinessException(
                    forEligibility
                            ? accountLookupError(restClientResponseException)
                            : ErrorCode.INTERNAL_SERVER_ERROR
            );
        } catch (RestClientException restClientException) {
            log.warn(
                    "User Service request failed, RestClientException={}",
                    restClientException.getMessage()
            );

            ErrorCode error = restClientException instanceof ResourceAccessException
                    ? ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE
                    : ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE;

            throw new BusinessException(
                    forEligibility
                            ? error
                            : ErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        if (response == null
                || response.user() == null
                || !userId.equals(response.user().id())
        ) {
            log.atWarn()
                    .addKeyValue("event", "dependency.user.invalid_response")
                    .log("User Service returned an invalid user response");

            throw new BusinessException(
                    forEligibility
                            ? ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE
                            : ErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        return response.user();
    }

    private ErrorCode accountLookupError(RestClientResponseException exception) {
        if (exception.getStatusCode().value() == 404) {
            try {
                JsonNode body = exception.getResponseBodyAs(JsonNode.class);

                if (body != null && "USER_NOT_FOUND".equals(body.path("code").asString())) {
                    return ErrorCode.DOCTOR_ACCOUNT_NOT_FOUND;
                }
            } catch (RuntimeException unreadable) {
                return ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE;
            }

            return ErrorCode.DOCTOR_ACCOUNT_INVALID_RESPONSE;
        }

        return ErrorCode.DOCTOR_ACCOUNT_UNAVAILABLE;
    }

    public record AccountIdentity(
            UUID id,
            boolean isActivated,
            int role
    ) {
    }

    private record UserLookupResponse(
            UserAccount user
    ) {
    }

    private record UserAccount(
            UUID id,
            String username,
            String email,
            JsonNode isActivated,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            JsonNode role
    ) {
    }
}
