package com.evinsurance.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class EvInsuranceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvInsuranceApplication.class, args);
    }
}

