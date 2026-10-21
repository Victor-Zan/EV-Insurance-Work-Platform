package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.identity.application.AuthService;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.foundation.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;private final com.evinsurance.platform.identity.application.AccountRateLimit limits;
    public AuthController(AuthService service,com.evinsurance.platform.identity.application.AccountRateLimit limits) { this.service=service;this.limits=limits; }
    @PostMapping("/login") @Operation(summary="账号密码登录指定端；无刷新 Token，过期重新登录")
    public ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody IdentityRequests.Login request,jakarta.servlet.http.HttpServletRequest http) { limits.take("login:"+http.getRemoteAddr(),20);return ApiResponse.success(service.login(request)); }
    @GetMapping("/me") @SecurityRequirement(name="bearerAuth") @Operation(summary="实时当前用户、角色与网点归属")
    public ApiResponse<CurrentUser> me() { return ApiResponse.success(CurrentUser.require()); }
}
