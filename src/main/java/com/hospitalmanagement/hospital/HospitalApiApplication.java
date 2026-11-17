package com.hospitalmanagement.hospital;

import com.hospitalmanagement.hospital.config.ServiceEndpointProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ServiceEndpointProperties.class)
public class HospitalApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalApiApplication.class, args);
    }

}
