package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.identity.application.UserService;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name="bearerAuth")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service=service; }
    @GetMapping("/users") @Operation(summary="分页读取用户（不返回密码哈希）")
    public ApiResponse<PageResponse<UserView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.list(page,size)); }
    @PostMapping("/users") @Operation(summary="管理员创建账号、角色、车主资料或网点归属")
    public ApiResponse<UserView> create(@Valid @RequestBody IdentityRequests.CreateUser request) { return ApiResponse.success(service.create(request)); }
    @PatchMapping("/users/{id}/enabled") @Operation(summary="管理员启停账号；旧 JWT 立即失效")
    public ApiResponse<Void> enabled(@PathVariable long id,@Valid @RequestBody IdentityRequests.Enabled request) { service.setEnabled(id,request.enabled()); return ApiResponse.success(null); }
    @PostMapping("/users/{id}/password-reset") @Operation(summary="管理员重置密码；不返回密码，旧 JWT 失效")
    public ApiResponse<Void> reset(@PathVariable long id,@Valid @RequestBody IdentityRequests.PasswordReset request) { service.resetPassword(id,request.password()); return ApiResponse.success(null); }
    @PutMapping("/users/{id}/assignment") @Operation(summary="管理员更改角色与单网点归属，原 Token 失效并审计")
    public ApiResponse<UserView> assignment(@PathVariable long id,@Valid @RequestBody IdentityRequests.Assignment request) { return ApiResponse.success(service.assignment(id,request)); }
    @GetMapping("/roles") @Operation(summary="分页读取四个固定角色；不允许新增角色类型")
    public ApiResponse<PageResponse<Role>> roles(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        int offset=PageResponse.offset(page,size);
        return ApiResponse.success(new PageResponse<>(page,size,Role.values().length,java.util.Arrays.stream(Role.values()).skip(offset).limit(size).toList()));
    }
}
