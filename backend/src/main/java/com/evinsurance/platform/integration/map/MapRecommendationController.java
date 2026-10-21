package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.workorder.api.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps") @SecurityRequirement(name="bearerAuth")
public class MapRecommendationController {
    private final MapRecommendationService service;
    public MapRecommendationController(MapRecommendationService service){this.service=service;}
    public record Query(@NotNull UUID locationToken,Long shopCityId){}
    @GetMapping("/cases/{id}/recommendation-context") public ApiResponse<MapRecommendationService.Context> context(@PathVariable UUID id){return ApiResponse.success(service.context(id));}
    @PostMapping("/cases/{id}/recommendations") public ApiResponse<MapRecommendationService.Result> query(@PathVariable UUID id,@Valid @RequestBody Query request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.query(id,request.locationToken(),request.shopCityId(),key));}
    @GetMapping("/recommendations/{id}") public ApiResponse<MapRecommendationService.Result> result(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.result(id,page,size));}
    @GetMapping("/cases/{id}/recommendation-exclusions") public ApiResponse<PageResponse<MapData.Shop>> excluded(@PathVariable UUID id,@RequestParam(required=false) Long shopCityId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.excluded(id,shopCityId,page,size));}
    @PostMapping("/recommendations/{id}/dispatch") public ApiResponse<WorkOrderViews.WorkOrder> dispatch(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Dispatch request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.dispatch(id,request,key));}
    @PostMapping("/recommendations/{id}/reassign") public ApiResponse<WorkOrderViews.WorkOrder> reassign(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Reassign request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.success(service.reassign(id,request,key));}
}
