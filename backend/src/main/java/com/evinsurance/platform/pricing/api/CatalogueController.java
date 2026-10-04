package com.evinsurance.platform.pricing.api;
import com.evinsurance.platform.pricing.application.CatalogueService;
import com.evinsurance.platform.pricing.infrastructure.*;
import com.evinsurance.platform.foundation.api.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
@RestController @RequestMapping("/api/v1/pricing") @Tag(name="Price catalogue")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class CatalogueController {
 private final CatalogueService service; public CatalogueController(CatalogueService service){this.service=service;}

 @GetMapping("/brands") public ApiResponse<PageResponse<BrandEntity>> brands(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String name){return ApiResponse.success(service.brands(page,size,name));}
 @PostMapping("/brands") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<BrandEntity> createBrand(@Valid @RequestBody CatalogueRequests.Brand r){return ApiResponse.success(service.saveBrand(null,r));}
 @PutMapping("/brands/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<BrandEntity> updateBrand(@PathVariable long id,@Valid @RequestBody CatalogueRequests.Brand r){return ApiResponse.success(service.saveBrand(id,r));}
 @DeleteMapping("/brands/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deleteBrand(@PathVariable long id){service.deleteBrand(id);return ApiResponse.success(null);}

 @GetMapping("/models") public ApiResponse<PageResponse<ModelEntity>> models(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long brandId,@RequestParam(required=false) String name){return ApiResponse.success(service.models(page,size,brandId,name));}
 @PostMapping("/models") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<ModelEntity> createModel(@Valid @RequestBody CatalogueRequests.Model r){return ApiResponse.success(service.saveModel(null,r));}
 @PutMapping("/models/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<ModelEntity> updateModel(@PathVariable long id,@Valid @RequestBody CatalogueRequests.Model r){return ApiResponse.success(service.saveModel(id,r));}
 @DeleteMapping("/models/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deleteModel(@PathVariable long id){service.deleteModel(id);return ApiResponse.success(null);}

 @GetMapping("/parts") public ApiResponse<PageResponse<PartEntity>> parts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String internalCode,@RequestParam(required=false) String name){return ApiResponse.success(service.parts(page,size,internalCode,name));}
 @PostMapping("/parts") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<PartEntity> createPart(@Valid @RequestBody CatalogueRequests.Part r){return ApiResponse.success(service.savePart(null,r));}
 @PutMapping("/parts/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<PartEntity> updatePart(@PathVariable long id,@Valid @RequestBody CatalogueRequests.Part r){return ApiResponse.success(service.savePart(id,r));}
 @DeleteMapping("/parts/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deletePart(@PathVariable long id){service.deletePart(id);return ApiResponse.success(null);}

 @GetMapping("/aliases") public ApiResponse<PageResponse<AliasEntity>> aliases(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long partId,@RequestParam(required=false) String name){return ApiResponse.success(service.aliases(page,size,partId,name));}
 @PostMapping("/aliases") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<AliasEntity> createAlias(@Valid @RequestBody CatalogueRequests.Alias r){return ApiResponse.success(service.saveAlias(null,r));}
 @PutMapping("/aliases/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<AliasEntity> updateAlias(@PathVariable long id,@Valid @RequestBody CatalogueRequests.Alias r){return ApiResponse.success(service.saveAlias(id,r));}
 @DeleteMapping("/aliases/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deleteAlias(@PathVariable long id){service.deleteAlias(id);return ApiResponse.success(null);}

 @GetMapping("/sources") public ApiResponse<PageResponse<SourceEntity>> sources(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.sources(page,size));}
 @PostMapping("/sources") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<SourceEntity> createSource(@Valid @RequestBody CatalogueRequests.Source r){return ApiResponse.success(service.saveSource(null,r));}
 @PutMapping("/sources/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<SourceEntity> updateSource(@PathVariable long id,@Valid @RequestBody CatalogueRequests.Source r){return ApiResponse.success(service.saveSource(id,r));}
 @DeleteMapping("/sources/{id}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deleteSource(@PathVariable long id){service.deleteSource(id);return ApiResponse.success(null);}

 @GetMapping("/part-models") public ApiResponse<PageResponse<CatalogueRelationsMapper.Relation>> relations(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long partId,@RequestParam(required=false) Long modelId){return ApiResponse.success(service.relations(page,size,partId,modelId));}
 @PostMapping("/part-models") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> addRelation(@Valid @RequestBody CatalogueRequests.Relation r){service.addRelation(r);return ApiResponse.success(null);}
 @DeleteMapping("/part-models/{partId}/{modelId}") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<Void> deleteRelation(@PathVariable long partId,@PathVariable long modelId){service.deleteRelation(partId,modelId);return ApiResponse.success(null);}
 @GetMapping("/regions") public ApiResponse<PageResponse<CatalogueRelationsMapper.Lookup>> regions(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.regions(page,size));}
 @GetMapping("/shops") public ApiResponse<PageResponse<CatalogueRelationsMapper.Lookup>> shops(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.shops(page,size));}
 @GetMapping("/shops/{id}/service-regions") public ApiResponse<PageResponse<CatalogueRelationsMapper.Lookup>> serviceRegions(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.serviceRegions(id,page,size));}
}
