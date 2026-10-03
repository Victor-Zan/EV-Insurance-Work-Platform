package com.evinsurance.platform.identity.infrastructure;

import java.util.Map;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("dev")
public class DevelopmentSeedConfiguration {
    @Bean
    FlywayConfigurationCustomizer developmentHashes(Environment env, PasswordEncoder encoder) {
        return config -> config.placeholders(Map.of(
            "devAdminHash", hash(env, encoder, "DEV_ADMIN_PASSWORD"),
            "devCustomerServiceHash", hash(env, encoder, "DEV_CUSTOMER_SERVICE_PASSWORD"),
            "devRepairShopHash", hash(env, encoder, "DEV_REPAIR_SHOP_PASSWORD"),
            "devOwnerHash", hash(env, encoder, "DEV_OWNER_PASSWORD")));
    }
    private String hash(Environment env, PasswordEncoder encoder, String key) {
        String value = env.getRequiredProperty(key);
        if (value.isEmpty() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Development credential must be nonempty and fit BCrypt: " + key);
        }
        return encoder.encode(value);
    }
}
