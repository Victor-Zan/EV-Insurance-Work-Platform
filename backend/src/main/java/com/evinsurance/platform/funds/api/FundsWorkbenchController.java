package com.evinsurance.platform.funds.api;

import com.evinsurance.platform.funds.application.FundsService;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/funds/workbench")
public class FundsWorkbenchController {
    private final FundsService service;
    public FundsWorkbenchController(FundsService service) { this.service=service; }
    @GetMapping @Operation(summary="管理员/客服分页筛选跨案件资金状态；不包含联系信息或内部报价")
    public ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String query,
        @RequestParam(required=false) String status,@RequestParam(required=false) String receivableStatus,
        @RequestParam(required=false) String payableStatus) {
        return ApiResponse.success(service.workbench(page,size,query,status,receivableStatus,payableStatus));
    }
}
