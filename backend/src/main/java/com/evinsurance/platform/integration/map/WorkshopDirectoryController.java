package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.workorder.api.WorkOrderViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps/cases/{caseId}/workshop-directory") @SecurityRequirement(name="bearerAuth")
public class WorkshopDirectoryController {
    private final WorkshopDirectoryService service;
    public WorkshopDirectoryController(WorkshopDirectoryService service){this.service=service;}
    @GetMapping @Operation(summary="客服分页搜索云端深圳候选门店，含停用待核验资料；不调用地图、不修改资格")
    public ApiResponse<PageResponse<WorkshopDirectoryService.Entry>> search(@PathVariable UUID caseId,@RequestParam(defaultValue="") String query,@RequestParam(required=false) Long district,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.search(caseId,query,district,page,size));}
    public record Point(@DecimalMin("-90") @DecimalMax("90") double latitude,@DecimalMin("-180") @DecimalMax("180") double longitude){}
    public record Prepare(@Positive long sourceShopId,@Valid Point point){}
    @PostMapping("/prepare") @Operation(summary="客服明确核对一家云端候选门店位置；最多一次地址解析或地图选点核验，不派单")
    public ApiResponse<WorkshopDirectoryService.Prepared> prepare(@PathVariable UUID caseId,@Valid @RequestBody Prepare r){return ApiResponse.success(service.prepare(caseId,r.sourceShopId(),r.point()==null?null:new MapProvider.Point(r.point().latitude(),r.point().longitude())));}
    public record Dispatch(@NotNull UUID planId,@Positive long sourceShopId,@NotNull UUID shopLocationToken,boolean verified,boolean confirmPossibleDuplicate,@Size(max=500) String duplicateReason){}
    @PostMapping("/dispatch") @Operation(summary="客服核对位置及承接意愿后，一次确认登记仅本案门店并派单；事务、幂等、重复案件及审计规则保持有效")
    public ApiResponse<WorkOrderViews.WorkOrder> dispatch(@PathVariable UUID caseId,@Valid @RequestBody Dispatch r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.dispatch(caseId,new WorkshopDirectoryService.Dispatch(r.planId(),r.sourceShopId(),r.shopLocationToken(),r.verified(),r.confirmPossibleDuplicate(),r.duplicateReason()),key));}
}
