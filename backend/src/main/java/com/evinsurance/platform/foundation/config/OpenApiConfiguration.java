package com.evinsurance.platform.foundation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info()
            .title("电动车保险维修案件协同平台 API")
            .version("v1")
            .description("MVP REST API"));
    }
}

