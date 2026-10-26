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
    private final MapLocationService service;private final MapCallBudget budget;private final org.springframework.core.env.Environment env;
    public MapLocationController(MapLocationService service,MapCallBudget budget,org.springframework.core.env.Environment env){this.service=service;this.budget=budget;this.env=env;}
    @GetMapping("/budget") public ApiResponse<MapCallBudget.Availability> budget(){service.configuration();return ApiResponse.success(budget.availability());}
    public record RuntimeConfiguration(String provider,boolean mock,String coordinateSystem,boolean recommendationsAvailable,boolean callsEnabled,Integer callsRemaining,String budgetReason,String buildCommit,boolean testEnvironment,boolean simulationDestinations){}
    @GetMapping("/configuration") @Operation(summary="地图供应商、预算与后端版本，不含密钥")
    public ApiResponse<RuntimeConfiguration> configuration(){var c=service.configuration();var b=budget.availability();return ApiResponse.success(new RuntimeConfiguration(c.provider(),c.mock(),c.coordinateSystem(),c.recommendationsAvailable(),b.callsEnabled(),b.remaining(),b.reason(),env.getProperty("APP_BUILD_COMMIT","UNKNOWN"),env.getProperty("MAP_TEST_SESSION_ENABLED",Boolean.class,false),env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))&&env.getProperty("MAP_SIMULATION_DESTINATIONS_ENABLED",Boolean.class,false)));}
    @GetMapping("/regions") @Operation(summary="广东城市/所选城市区县，稳定分页，仅客服")
    public ApiResponse<PageResponse<MapLocationService.RegionOption>> regions(@RequestParam(required=false) Long cityId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.regions(cityId,page,size));}
    @GetMapping("/{token}") @Operation(summary="读取本人当前地址候选，不调用供应商")
    public ApiResponse<MapLocationService.Choice> choice(@PathVariable UUID token){var session=service.session(token,false);return ApiResponse.success(new MapLocationService.Choice(token,session.candidate()));}
    public record Resolve(@Positive long districtId,@NotBlank @Size(min=4,max=200) String detail){}
    @PostMapping("/resolve") @Operation(summary="解析车辆停放地址候选；需人工确认，不更改案件地址或派单")
    public ApiResponse<List<MapLocationService.Choice>> resolve(@Valid @RequestBody Resolve request){return ApiResponse.success(service.resolve(request.districtId(),request.detail()));}
    public record Places(@Positive long districtId,@NotBlank @Size(min=4,max=80) String keywords,@Min(1) @Max(20) int page){}
    @PostMapping("/places") @Operation(summary="客服明确搜索地点名称：一页10个候选，单独POI_SEARCH额度；不自动确认、补查或创建合作网点")
    public ApiResponse<MapLocationService.PlaceChoices> places(@Valid @RequestBody Places request){return ApiResponse.success(service.places(request.districtId(),request.keywords(),request.page()));}
    public record SelectedPoint(@Positive long districtId,@NotNull @DecimalMin("-180") @DecimalMax("180") java.math.BigDecimal longitude,@NotNull @DecimalMin("-90") @DecimalMax("90") java.math.BigDecimal latitude){}
    @PostMapping("/point") @Operation(summary="客服明确核验地图选点：最多一次REVERSE，核对所选广东区县；不写案件")
    public ApiResponse<List<MapLocationService.Choice>> selectedPoint(@Valid @RequestBody SelectedPoint request){return ApiResponse.success(service.selectedPoint(request.districtId(),new MapProvider.Point(request.latitude().doubleValue(),request.longitude().doubleValue())));}
    @PostMapping("/{token}/confirm") @Operation(summary="客服明确确认当前停车位置，不更新工单地址")
    public ApiResponse<Void> confirm(@PathVariable UUID token){service.confirm(token);return ApiResponse.success(null);}
    @GetMapping("/admin-regions") public ApiResponse<PageResponse<MapLocationService.RegionOption>> adminRegions(@RequestParam(required=false) Long cityId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.adminRegions(cityId,page,size));}
}
