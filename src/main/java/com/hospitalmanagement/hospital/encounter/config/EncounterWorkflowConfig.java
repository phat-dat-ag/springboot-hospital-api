package com.hospitalmanagement.hospital.encounter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class EncounterWorkflowConfig {
    @Bean
    public Clock clinicClock(
            @Value("${app.clinic.time-zone:Asia/Bangkok}")
            String timeZone
    ) {
        return Clock.system(ZoneId.of(timeZone));
    }
}

