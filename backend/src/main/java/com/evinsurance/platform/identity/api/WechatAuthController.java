package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.application.WechatIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
public class WechatAuthController {
    private final WechatIdentityService service;
    public WechatAuthController(WechatIdentityService service) { this.service=service; }
    private <T> ResponseEntity<ApiResponse<T>> success(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
    @PostMapping("/api/v1/auth/wechat/login") @Operation(summary="微信code直接登录；新微信原子创建车主账号，角色与网点由管理员设置")
    public ResponseEntity<ApiResponse<WechatViews.LoginResult>> login(@Valid @RequestBody WechatRequests.Login body,HttpServletRequest request) {
        service.publicRequest(request.getRemoteAddr());return success(service.login(body));
    }
    @PostMapping("/api/v1/auth/wechat/bindings") @Operation(summary="验证原账号密码后明确绑定当前微信；不覆盖有效绑定")
    public ResponseEntity<ApiResponse<WechatViews.LoginResult>> bind(@Valid @RequestBody WechatRequests.Bind body,HttpServletRequest request) {
        service.publicRequest(request.getRemoteAddr());return success(service.bind(body));
    }
    @PostMapping("/api/v1/auth/wechat/activation/inspect") @Operation(summary="仅预览脱敏邀请身份；不消费、不激活")
    public ResponseEntity<ApiResponse<WechatViews.Preview>> inspect(@Valid @RequestBody WechatRequests.Inspect body,HttpServletRequest request) {
        service.publicRequest(request.getRemoteAddr());return success(service.inspect(body));
    }
    @PostMapping("/api/v1/auth/wechat/activation/complete") @Operation(summary="同事务设置H5密码、激活、微信绑定、消费邀请并签发会话")
    public ResponseEntity<ApiResponse<WechatViews.LoginResult>> activate(@Valid @RequestBody WechatRequests.Activate body,HttpServletRequest request) {
        service.publicRequest(request.getRemoteAddr());return success(service.activate(body));
    }
    @PostMapping("/api/v1/admin/users/{id}/wechat-invitation") @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name="bearerAuth") @Operation(summary="管理员签发既有待激活移动账号的小程序邀请；重发撤销旧邀请")
    public ResponseEntity<ApiResponse<WechatViews.Invitation>> issue(@PathVariable long id) { return success(service.issue(id)); }
    @PostMapping("/api/v1/work-orders/{id}/owner-wechat-invitation") @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @SecurityRequirement(name="bearerAuth") @Operation(summary="沿原案件人工核实和范围规则开通车主；不自动按手机号认领")
    public ResponseEntity<ApiResponse<WechatViews.Invitation>> owner(@PathVariable UUID id,@Valid @RequestBody WechatRequests.OwnerInvitation body) {
        return success(service.ownerInvitation(id,body));
    }
    @GetMapping("/api/v1/admin/users/{id}/wechat-bindings") @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name="bearerAuth") @Operation(summary="分页读取绑定和撤销历史；不返回OpenID")
    public ResponseEntity<ApiResponse<PageResponse<WechatViews.Binding>>> bindings(@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return success(service.list(id,page,size)); }
    @PostMapping("/api/v1/admin/users/{id}/wechat-bindings/{bindingId}/revoke") @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name="bearerAuth") @Operation(summary="管理员核实撤销指定绑定；旧H5和微信会话全部失效，历史保留")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable long id,@PathVariable UUID bindingId,@Valid @RequestBody WechatRequests.Revoke body) {
        service.revoke(id,bindingId,body);return success(null);
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> failure(ApiException error) {
        var response=ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());
        if(error.status()==HttpStatus.TOO_MANY_REQUESTS)response.header(HttpHeaders.RETRY_AFTER,"600");
        return response.body(ApiResponse.failure(error.code(),error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> malformed(Exception error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(ApiResponse.failure("VALIDATION_ERROR","请求字段无效"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(DataAccessException error) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.failure("AUTH_UNAVAILABLE","身份服务暂不可用，请稍后重试"));
    }
}
