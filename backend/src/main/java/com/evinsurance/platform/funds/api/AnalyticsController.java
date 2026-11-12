package com.evinsurance.platform.funds.api;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.funds.application.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/funds/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service=service; }
    @GetMapping @Operation(summary="经营指标、趋势和分页明细；仅有效完成事件，客服限定创建者本人")
    public ApiResponse<Map<String,Object>> dashboard(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long shop,@RequestParam(required=false) Long creator,
        @RequestParam(defaultValue="false") boolean pending,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) LocalDate detailFrom,@RequestParam(required=false) LocalDate detailTo) {
        return ApiResponse.success(service.dashboard(from,to,shop,creator,pending,page,size,detailFrom,detailTo));
    }
    @GetMapping("/groups") @Operation(summary="分页网点贡献或管理员客服排名；候选项遵循同一数据权限")
    public ApiResponse<PageResponse<Map<String,Object>>> groups(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long shop,@RequestParam(required=false) Long creator,
        @RequestParam(defaultValue="shop") String dimension,@RequestParam(defaultValue="profit") String metric,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.groups(from,to,shop,creator,dimension,metric,page,size));
    }
}
