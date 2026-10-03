package com.evinsurance.platform.organization.api;

import com.evinsurance.platform.organization.application.OrganizationService;
import com.evinsurance.platform.organization.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name="bearerAuth")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service=service; }
    @GetMapping("/regions") @Operation(summary="分页读取区域树节点，可按父级筛选")
    public ApiResponse<PageResponse<RegionEntity>> regions(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long parentId) { return ApiResponse.success(service.regions(page,size,parentId)); }
    @PostMapping("/regions") @Operation(summary="创建省、市或区县")
    public ApiResponse<RegionEntity> createRegion(@Valid @RequestBody OrganizationRequests.Region request) { return ApiResponse.success(service.saveRegion(null,request)); }
    @PutMapping("/regions/{id}") @Operation(summary="更新区域并审计，校验父子层级")
    public ApiResponse<RegionEntity> updateRegion(@PathVariable long id,@Valid @RequestBody OrganizationRequests.Region request) { return ApiResponse.success(service.saveRegion(id,request)); }
    @GetMapping("/shops") @Operation(summary="分页读取维修网点")
    public ApiResponse<PageResponse<ShopEntity>> shops(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.shops(page,size)); }
    @PostMapping("/shops") @Operation(summary="创建维修网点")
    public ApiResponse<ShopEntity> createShop(@Valid @RequestBody OrganizationRequests.Shop request) { return ApiResponse.success(service.saveShop(null,request)); }
    @PutMapping("/shops/{id}") @Operation(summary="更新网点基础信息与启用状态，并审计")
    public ApiResponse<ShopEntity> updateShop(@PathVariable long id,@Valid @RequestBody OrganizationRequests.Shop request) { return ApiResponse.success(service.saveShop(id,request)); }
    @GetMapping("/shops/{id}/service-regions") @Operation(summary="分页读取网点服务区域 ID")
    public ApiResponse<PageResponse<Long>> serviceRegions(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.serviceRegions(id,page,size)); }
    @PutMapping("/shops/{id}/service-regions") @Operation(summary="替换网点服务区域集合（至多 100），并审计")
    public ApiResponse<Void> setServiceRegions(@PathVariable long id,@Valid @RequestBody OrganizationRequests.ServiceRegions request) { service.setServiceRegions(id,request.regionIds()); return ApiResponse.success(null); }
}
