package com.evinsurance.platform.foundation.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info().title("电动车保险维修案件协同平台 API").version("v1")
            .description("阶段 4：在阶段 3 价格能力上增加服务器端草稿、正式案件、重复判断、派单版本、接拒单、取消/改派、到店异常、继续等待、整单取消与到店确认。案件内部 UUID，正式编号 EVR-YYYYMMDD-######；服务端执行角色、数据范围、敏感字段可见性、显式状态机、幂等与审计。文件、OCR 和地图留到阶段 5。统一响应 code/message/data/traceId。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
    @Bean org.springdoc.core.customizers.OperationCustomizer unifiedErrorResponses() {
        return (operation, handler) -> {
            var schema=new io.swagger.v3.oas.models.media.ObjectSchema()
                .addProperty("code",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("message",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("traceId",new io.swagger.v3.oas.models.media.StringSchema());
            java.util.Map.of("400","参数错误","401","未认证、账号停用或 Token 失效","403","无权限或登录错误端",
                "404","资源不存在","409","状态、幂等、重复、价格版本或关联约束冲突","410","导入预览已过期","413","导入文件或行数超限","503","认证依赖暂不可用")
                .forEach((status,description) -> operation.getResponses().addApiResponse(status,
                    new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                        .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType().schema(schema)))));
            return operation;
        };
    }
}
