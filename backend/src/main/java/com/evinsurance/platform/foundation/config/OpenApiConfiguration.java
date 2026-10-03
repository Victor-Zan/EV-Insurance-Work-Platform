package com.evinsurance.platform.foundation.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info().title("电动车保险维修案件协同平台 API").version("v1")
            .description("阶段 2：账号密码、JWT（无刷新 Token）、ADMIN 基础管理及审计。统一响应 code/message/data/traceId；400 参数、401 未认证/失效、403 无权限、404 不存在、409 冲突。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
    @Bean org.springdoc.core.customizers.OperationCustomizer unifiedErrorResponses() {
        return (operation, handler) -> {
            var schema=new io.swagger.v3.oas.models.media.ObjectSchema()
                .addProperty("code",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("message",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("traceId",new io.swagger.v3.oas.models.media.StringSchema());
            java.util.Map.of("400","参数错误","401","未认证、账号停用或 Token 失效","403","无权限或登录错误端",
                "404","资源不存在","409","唯一或关联约束冲突","503","认证数据库暂不可用")
                .forEach((status,description) -> operation.getResponses().addApiResponse(status,
                    new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                        .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType().schema(schema)))));
            return operation;
        };
    }
}
