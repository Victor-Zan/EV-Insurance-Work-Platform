package com.evinsurance.platform.health.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.evinsurance.platform.foundation.config.SecurityConfiguration;
import com.evinsurance.platform.foundation.web.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import({SecurityConfiguration.class, RequestTraceFilter.class})
class HealthControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.evinsurance.platform.identity.infrastructure.JwtService jwtService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.evinsurance.platform.identity.infrastructure.UserMapper userMapper;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsUnifiedHealthyResponseWithTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(header().string(RequestTraceFilter.REQUEST_ID_HEADER, matchesPattern("[a-f0-9]{32}")))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.service").value("ev-insurance-backend"))
            .andExpect(jsonPath("$.traceId", matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    void preservesSafeCallerRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/health").header(RequestTraceFilter.REQUEST_ID_HEADER, "local-check-1"))
            .andExpect(status().isOk())
            .andExpect(header().string(RequestTraceFilter.REQUEST_ID_HEADER, "local-check-1"))
            .andExpect(jsonPath("$.traceId").value("local-check-1"));
    }

    @Test
    void returnsUnifiedResponseForDeniedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/not-implemented"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication is required"))
            .andExpect(jsonPath("$.traceId", matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    void allowsBothManagementLoopbackNamesWhileKeepingAuthenticationAndOriginChecks() throws Exception {
        for (var origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mockMvc.perform(options("/api/v1/auth/login")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
            mockMvc.perform(get("/api/v1/admin/session").header("Origin", origin))
                .andExpect(status().isUnauthorized());
        }
        for (var origin : new String[]{"http://127.0.0.1:5199", "https://untrusted.invalid"}) {
            mockMvc.perform(options("/api/v1/auth/login")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void allowsCurrentLocalH5OriginWithoutOpeningCorsToOtherOrigins() throws Exception {
        for (var origin : new String[]{"http://127.0.0.1:5174", "http://localhost:5175"}) {
            mockMvc.perform(options("/api/v1/auth/login")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }

        mockMvc.perform(options("/api/v1/auth/login")
                .header("Origin", "http://localhost:5199")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
    }
}
