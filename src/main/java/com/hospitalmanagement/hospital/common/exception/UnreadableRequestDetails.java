package com.hospitalmanagement.hospital.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Extracts schema-owned diagnostics without retaining rejected values or parser messages.
 */
@Component
@RequiredArgsConstructor
public class UnreadableRequestDetails {
    private final ObjectMapper objectMapper;

    public Optional<InvalidEnum> invalidEnum(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFormatException invalid
                    && invalid.getTargetType() != null
                    && invalid.getTargetType().isEnum()
            ) {
                Object[] constants = invalid.getTargetType().getEnumConstants();

                List<String> allowedValues = Arrays.stream(constants)
                        .<JsonNode>map(objectMapper::valueToTree)
                        .filter(JsonNode::isString)
                        .map(JsonNode::asString)
                        .toList();

                if (!allowedValues.isEmpty()) {
                    return Optional.of(
                            new InvalidEnum(recordFieldPath(invalid), allowedValues)
                    );
                }
            }
        }

        return Optional.empty();
    }

    private String recordFieldPath(InvalidFormatException failure) {
        if (failure.getPath().size() > 8) {
            return "request";
        }

        StringBuilder path = new StringBuilder();

        for (Reference reference : failure.getPath()) {
            String property = reference.getPropertyName();

            if (property != null) {
                Object source = reference.from();

                if (source == null) {
                    return "request";
                }

                Class<?> owner = source instanceof Class<?> type ? type : source.getClass();
                // Map keys and arbitrary parser paths may contain user data.
                if (!owner.isRecord()
                        || Arrays.stream(owner.getRecordComponents())
                        .noneMatch(component -> component.getName().equals(property))
                ) {
                    return "request";
                }

                if (!path.isEmpty()) {
                    path.append('.');
                }

                path.append(property);
            } else if (reference.getIndex() >= 0 && reference.getIndex() < 1_000_000) {
                path.append('[').append(reference.getIndex()).append(']');
            } else {
                return "request";
            }
        }

        return path.isEmpty() || path.length() > 128 ? "request" : path.toString();
    }

    public record InvalidEnum(String field, List<String> allowedValues) {
        public InvalidEnum {
            allowedValues = List.copyOf(allowedValues);
        }
    }
}
