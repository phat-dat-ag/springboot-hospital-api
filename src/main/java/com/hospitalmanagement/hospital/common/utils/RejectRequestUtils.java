package com.hospitalmanagement.hospital.common.utils;

import com.hospitalmanagement.hospital.common.exception.BusinessException;
import com.hospitalmanagement.hospital.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RejectRequestUtils {
    public static BusinessException reject(
            String event,
            String stage,
            String logMessage,
            ErrorCode errorCode
    ) {
        log.atWarn()
                .addKeyValue("event", event)
                .addKeyValue("stage", stage)
                .addKeyValue("errorCode", errorCode.name())
                .log(logMessage);

        return new BusinessException(errorCode);
    }
}
