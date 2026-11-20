package com.hospitalmanagement.hospital.encounter.read;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

record HistoryCursor(Instant time, UUID id) {
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    static HistoryCursor parse(String value) {
        if (value == null) {
            return null;
        }

        if (value.length() > 80) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String[] parts = value.split("~", -1);
        try {
            if (parts.length == 2) {
                Instant time = Instant.parse(parts[0]);
                UUID id = UUID.fromString(parts[1]);
                if (id.toString().equals(parts[1]) && !time.isBefore(MIN) && !time.isAfter(MAX)) {
                    return new HistoryCursor(time, id);
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    OffsetDateTime utc() {
        return time.atOffset(ZoneOffset.UTC);
    }

    String encode() {
        return time + "~" + id;
    }
}
