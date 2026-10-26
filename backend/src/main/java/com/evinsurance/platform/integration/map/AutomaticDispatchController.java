package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.workorder.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps/cases/{caseId}/automatic-dispatch") @SecurityRequirement(name="bearerAuth")
public class AutomaticDispatchController {
    private final AutomaticDispatchService service;
    public AutomaticDispatchController(AutomaticDispatchService service){this.service=service;}
    public record Prepare(@Size(max=200) String address,boolean corrected,@Positive Long districtId,@Valid Point point,boolean retry,@NotNull UUID requestKey){}
    public record Point(@DecimalMin("-90") @DecimalMax("90") double latitude,@DecimalMin("-180") @DecimalMax("180") double longitude){}
    @PostMapping @Operation(summary="仅客服初次派单：去重定位及合作网点道路推荐，不写案件位置、不派单；失败/过期须显式重试")
    public ApiResponse<AutomaticDispatchService.Result> prepare(@PathVariable UUID caseId,@Valid @RequestBody Prepare request){return ApiResponse.success(service.prepare(caseId,new AutomaticDispatchService.Input(request.address(),request.corrected(),request.districtId(),request.point()==null?null:new MapProvider.Point(request.point().latitude(),request.point().longitude()),request.retry(),request.requestKey())));}
    @GetMapping("/{id}") @Operation(summary="读取本客服当前推荐任务，复核案件/网点版本，不调用地图供应商")
    public ApiResponse<AutomaticDispatchService.Result> result(@PathVariable UUID caseId,@PathVariable UUID id){return ApiResponse.success(service.result(caseId,id));}
    @PostMapping("/{id}/dispatch") @Operation(summary="一次确认：允许唯一有效路段定位，事务内保存位置及目的地快照并沿用原派单/幂等/审计")
    public ApiResponse<WorkOrderViews.WorkOrder> dispatch(@PathVariable UUID caseId,@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Dispatch request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.dispatch(caseId,id,request,key));}
}
