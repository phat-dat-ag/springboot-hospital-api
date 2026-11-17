package com.hospitalmanagement.hospital.common.exception;

import com.hospitalmanagement.hospital.patient.enums.SexAtBirth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnreadableRequestDetailsTest {
    private final UnreadableRequestDetails details = new UnreadableRequestDetails(
            JsonMapper.builder().build()
    );

    @Test
    void doesNotCopyUserControlledMapKeysOrRejectedValuesIntoDiagnostics() {
        InvalidFormatException failure = InvalidFormatException.from(
                null,
                "private-parser-message",
                "private-rejected-value",
                SexAtBirth.class
        );
        failure.prependPath(Map.class, "private-map-key");

        var diagnostic = details.invalidEnum(failure).orElseThrow();

        assertThat(diagnostic.field()).isEqualTo("request");
        assertThat(diagnostic.allowedValues())
                .containsExactly("female", "male", "intersex", "unknown");
        assertThat(diagnostic.toString())
                .doesNotContain(
                        "private-parser-message",
                        "private-rejected-value",
                        "private-map-key"
                );
    }

    @Test
    void leavesNonEnumParsingFailuresToTheExistingGenericHandler() {
        InvalidFormatException failure = InvalidFormatException.from(
                null,
                "private-parser-message",
                "private-rejected-value",
                LocalDate.class
        );

        assertThat(details.invalidEnum(failure)).isEmpty();
    }
}
