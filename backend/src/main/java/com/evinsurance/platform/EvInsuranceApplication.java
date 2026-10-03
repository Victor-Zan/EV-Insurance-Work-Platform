package com.evinsurance.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class EvInsuranceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvInsuranceApplication.class, args);
    }
}

