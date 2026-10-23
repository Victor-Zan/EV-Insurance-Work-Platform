package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.organization.application.CaseWorkshopRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps/public-workshops") @SecurityRequirement(name="bearerAuth")
public class PublicWorkshopController {
    private final PublicWorkshopService service;
    public PublicWorkshopController(PublicWorkshopService service){this.service=service;}
    @GetMapping("/configuration") public ApiResponse<PublicWorkshopService.Settings> configuration(){return ApiResponse.success(service.settings());}
    public record Search(@NotNull UUID caseId,@NotNull UUID locationToken,@Min(1) @Max(10) int page){}
    @PostMapping("/search") @Operation(summary="客服明确查询：10公里、每页最多20个公开两轮车维修候选；共享POI_SEARCH，不自动算路或派单")
    public ApiResponse<PublicWorkshopService.Result> search(@Valid @RequestBody Search request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.search(request.caseId(),request.locationToken(),request.page(),key));}
    public record Manual(@NotNull UUID caseId,UUID queryId,@NotNull UUID parkingToken,@NotNull UUID shopLocationToken,@NotBlank @Size(min=2,max=80) String name){}
    @PostMapping("/manual") public ApiResponse<PublicWorkshopService.Result> manual(@Valid @RequestBody Manual r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.manual(r.caseId(),r.queryId(),r.parkingToken(),r.shopLocationToken(),r.name(),key));}
    public record Routes(@NotNull UUID queryId,@NotEmpty @Size(max=25) List<@NotNull UUID> selected){}
    @PostMapping("/routes") @Operation(summary="明确计算选定集合车辆→门店策略32道路距离；先预留整个集合额度，未知留空")
    public ApiResponse<PublicWorkshopService.Result> routes(@Valid @RequestBody Routes r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.routes(r.queryId(),r.selected(),key));}
    @GetMapping("/{id}") public ApiResponse<PublicWorkshopService.Result> result(@PathVariable UUID id){return ApiResponse.success(service.result(id));}
    public record Temporary(@NotNull UUID queryId,@NotNull UUID candidateId,@NotBlank @Size(min=2,max=80) String name,@NotBlank @Size(min=4,max=255) String address,@AssertTrue boolean verified){}
    @PostMapping("/temporary-shop") @Operation(summary="客服核实后登记本案临时门店；不建账号、不派单，不扩大通用网点管理权限")
    public ApiResponse<CaseWorkshopRegistry.TemporaryShop> temporary(@Valid @RequestBody Temporary r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.temporary(r.queryId(),r.candidateId(),r.name(),r.address(),r.verified(),key));}
}
