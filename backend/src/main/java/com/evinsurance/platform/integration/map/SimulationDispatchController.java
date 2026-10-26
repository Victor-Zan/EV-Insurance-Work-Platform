package com.evinsurance.platform.integration.map;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps") @SecurityRequirement(name="bearerAuth")
public class SimulationDispatchController {
    private final SimulationDispatchService service;
    public SimulationDispatchController(SimulationDispatchService service){this.service=service;}
    @GetMapping("/simulation-shops") @Operation(summary="仅显式dev模拟模式的客服：分页读取已导入模拟目的地，不调用地图")
    public ApiResponse<PageResponse<MapRecommendationService.Row>> catalog(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.catalog(page,size));}
    @GetMapping("/simulation-recommendations/{id}") @Operation(summary="读取本人模拟道路比较，不重新计算；不开放其他网点账号案件")
    public ApiResponse<MapRecommendationService.Result> result(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.result(id,page,size));}
}
