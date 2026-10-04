package com.evinsurance.platform.foundation.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info().title("电动车保险维修案件协同平台 API").version("v1")
            .description("阶段 3：复用账号/JWT/组织/审计；ADMIN 与 CUSTOMER_SERVICE 可维护日常价格资料、创建版本、预览和原子导入、查询批次及错误；来源字典与用户/角色/组织/审计管理仅 ADMIN。CNY 正金额、中国自然日首尾包含，新版本追加并自动截止无期限旧版本，历史金额/来源/创建信息不可修改，无自动适用优先级。统一响应 code/message/data/traceId。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
    @Bean org.springdoc.core.customizers.OperationCustomizer unifiedErrorResponses() {
        return (operation, handler) -> {
            var schema=new io.swagger.v3.oas.models.media.ObjectSchema()
                .addProperty("code",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("message",new io.swagger.v3.oas.models.media.StringSchema())
                .addProperty("traceId",new io.swagger.v3.oas.models.media.StringSchema());
            java.util.Map.of("400","参数错误","401","未认证、账号停用或 Token 失效","403","无权限或登录错误端",
                "404","资源不存在","409","价格版本、唯一或关联约束冲突","410","导入预览已过期","413","导入文件或行数超限","503","认证数据库暂不可用")
                .forEach((status,description) -> operation.getResponses().addApiResponse(status,
                    new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                        .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType().schema(schema)))));
            return operation;
        };
    }
}
