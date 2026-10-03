package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.identity.domain.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name="bearerAuth")
public class PortalController {
    @GetMapping("/api/v1/admin/session") @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="管理端身份入口（仅管理员与客服）")
    public ApiResponse<CurrentUser> admin() { return ApiResponse.success(CurrentUser.require()); }
    @GetMapping("/api/v1/h5/session") @PreAuthorize("hasAnyRole('REPAIR_SHOP','OWNER')")
    @Operation(summary="H5 身份入口（仅网点与车主）")
    public ApiResponse<CurrentUser> h5() { return ApiResponse.success(CurrentUser.require()); }
}
