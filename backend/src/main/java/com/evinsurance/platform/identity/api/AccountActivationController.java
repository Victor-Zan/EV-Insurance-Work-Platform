package com.evinsurance.platform.identity.api;
import com.evinsurance.platform.identity.application.*;
import com.evinsurance.platform.identity.domain.Portal;
import com.evinsurance.platform.foundation.api.*;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@RestController
public class AccountActivationController {
    private final AccountActivationService accounts;private final UnifiedLoginService login;private final AccountRateLimit limits;
    public AccountActivationController(AccountActivationService accounts,UnifiedLoginService login,AccountRateLimit limits){this.accounts=accounts;this.login=login;this.limits=limits;}
    public record Credential(@Size(max=128) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token){}
    public record Complete(@Size(max=128) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token,@Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password,@Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String confirmPassword){}
    public record SignIn(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password){}
    public record Handoff(@Size(max=128) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code,Portal portal){}
    @PostMapping("/api/v1/auth/activation/inspect") @Operation(summary="只验证邀请并返回脱敏身份；不激活，无GET副作用")
    public ApiResponse<?> inspect(@Valid @RequestBody Credential body,HttpServletRequest request){limits.take("activation:"+request.getRemoteAddr(),20);return ApiResponse.success(accounts.inspect(body.token()));}
    @PostMapping("/api/v1/auth/activation/complete") @Operation(summary="24小时一次性邀请设置BCrypt密码并激活；不能启用停用账户")
    public ApiResponse<?> complete(@Valid @RequestBody Complete body,HttpServletRequest request){limits.take("activation:"+request.getRemoteAddr(),20);return ApiResponse.success(accounts.activate(body.token(),body.password(),body.confirmPassword()));}
    @PostMapping("/api/v1/auth/sign-in") @Operation(summary="统一登录，无前端角色参数；返回短时一次性交接码，不通过URL传JWT")
    public ApiResponse<?> signIn(@Valid @RequestBody SignIn body,HttpServletRequest request){limits.take("login:"+request.getRemoteAddr(),20);return ApiResponse.success(login.signIn(body.username(),body.password()));}
    @PostMapping("/api/v1/auth/handoff") public ApiResponse<?> handoff(@Valid @RequestBody Handoff body,HttpServletRequest request){limits.take("handoff:"+request.getRemoteAddr(),30);return ApiResponse.success(login.redeem(body.code(),body.portal()));}
    @PostMapping("/api/v1/admin/account-invitations/accounts") @SecurityRequirement(name="bearerAuth") @Operation(summary="仅ADMIN准备待激活管理员/客服/有效网点账号并签发邀请") public ApiResponse<?> prepare(@RequestBody AccountActivationService.Prepare body){return ApiResponse.success(accounts.prepare(body));}
    @PostMapping("/api/v1/admin/account-invitations/users/{id}") @SecurityRequirement(name="bearerAuth") @Operation(summary="仅ADMIN签发或重发24小时邀请，旧邀请立即失效") public ApiResponse<?> issue(@PathVariable long id){return ApiResponse.success(accounts.adminIssue(id));}
    @GetMapping("/api/v1/admin/account-invitations/users/{id}") @SecurityRequirement(name="bearerAuth") @Operation(summary="仅ADMIN读取邀请及激活状态，不取回明文邀请") public ApiResponse<?> status(@PathVariable long id){return ApiResponse.success(accounts.status(id));}
    @PostMapping("/api/v1/work-orders/{id}/owner-invitation") @SecurityRequirement(name="bearerAuth") @Operation(summary="客服仅明确案件的OWNER开通；人工核实、版本和范围校验；不能自选其他角色")
    public ApiResponse<?> owner(@PathVariable UUID id,@RequestBody AccountActivationService.OwnerRequest body){return ApiResponse.success(accounts.owner(id,body));}
}
