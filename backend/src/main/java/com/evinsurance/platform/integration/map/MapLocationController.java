package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps/locations") @SecurityRequirement(name="bearerAuth")
public class MapLocationController {
    private final MapLocationService service;
    public MapLocationController(MapLocationService service){this.service=service;}
    @GetMapping("/configuration") @Operation(summary="客服地图供应商与能力标识，不含密钥")
    public ApiResponse<MapLocationService.Configuration> configuration(){return ApiResponse.success(service.configuration());}
    @GetMapping("/regions") @Operation(summary="广东城市/所选城市区县，稳定分页，仅客服")
    public ApiResponse<PageResponse<MapLocationService.RegionOption>> regions(@RequestParam(required=false) Long cityId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.regions(cityId,page,size));}
    public record Resolve(@Positive long districtId,@NotBlank @Size(min=4,max=200) String detail){}
    @PostMapping("/resolve") @Operation(summary="解析车辆停放地址候选；需人工确认，不更改案件地址或派单")
    public ApiResponse<List<MapLocationService.Choice>> resolve(@Valid @RequestBody Resolve request){return ApiResponse.success(service.resolve(request.districtId(),request.detail()));}
    @PostMapping("/{token}/confirm") @Operation(summary="客服明确确认当前停车位置，不更新工单地址")
    public ApiResponse<Void> confirm(@PathVariable UUID token){service.confirm(token);return ApiResponse.success(null);}
    @GetMapping("/admin-regions") public ApiResponse<PageResponse<MapLocationService.RegionOption>> adminRegions(@RequestParam(required=false) Long cityId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.adminRegions(cityId,page,size));}
}
