package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/maps/admin/shops/{shopId}") @SecurityRequirement(name="bearerAuth")
public class MapShopController {
    private final MapShopService service;private final MapTestPositions test;
    public MapShopController(MapShopService service,MapTestPositions test){this.service=service;this.test=test;}
    public record TestInput(@Positive long districtId,@NotBlank @Size(min=4,max=200) String detail){}
    @PostMapping("/test-location/resolve") public ApiResponse<List<MapTestPositions.Choice>> resolve(@PathVariable long shopId,@Valid @RequestBody TestInput input){return ApiResponse.success(test.resolve(shopId,input.districtId(),input.detail()));}
    @GetMapping("/test-location/{token}") public ApiResponse<MapTestPositions.Choice> choice(@PathVariable long shopId,@PathVariable java.util.UUID token){return ApiResponse.success(test.choice(shopId,token));}
    @PostMapping("/test-location/{token}/confirm") public ApiResponse<MapData.Location> confirm(@PathVariable long shopId,@PathVariable java.util.UUID token){return ApiResponse.success(test.confirm(shopId,token));}
    public record Capabilities(@NotNull @Size(max=100) List<@NotNull @Positive Long> modelIds){}
    public record Position(@Positive long districtId,@NotNull @Digits(integer=3,fraction=6) @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                           @NotNull @Digits(integer=2,fraction=6) @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,boolean gcj02Confirmed,boolean synthetic){}
    @GetMapping("/capabilities") public ApiResponse<PageResponse<Long>> capabilities(@PathVariable long shopId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.capabilities(shopId,page,size));}
    @PutMapping("/capabilities") public ApiResponse<Void> capabilities(@PathVariable long shopId,@Valid @RequestBody Capabilities request){service.capabilities(shopId,request.modelIds());return ApiResponse.success(null);}
    @GetMapping("/location") public ApiResponse<MapData.Location> location(@PathVariable long shopId){return ApiResponse.success(service.location(shopId));}
    @PostMapping("/location") public ApiResponse<Void> location(@PathVariable long shopId,@Valid @RequestBody Position request){service.saveLocation(shopId,request.districtId(),request.longitude().doubleValue(),request.latitude().doubleValue(),request.gcj02Confirmed(),request.synthetic());return ApiResponse.success(null);}
}
