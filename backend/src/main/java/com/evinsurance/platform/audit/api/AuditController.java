package com.evinsurance.platform.audit.api;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.infrastructure.AuditEntity;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {
    private final AuditService service;
    public AuditController(AuditService service) { this.service=service; }
    @GetMapping @Operation(summary="分页读取只追加审计日志（ADMIN）")
    public ApiResponse<PageResponse<AuditEntity>> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.list(page,size));
    }
}
