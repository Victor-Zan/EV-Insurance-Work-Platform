package com.evinsurance.platform.pricing.api;
import com.evinsurance.platform.pricing.application.PriceService;
import com.evinsurance.platform.pricing.infrastructure.PriceView;
import com.evinsurance.platform.foundation.api.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
@RestController @RequestMapping("/api/v1/pricing") @Tag(name="Reference prices")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class PriceController {
 private final PriceService service;public PriceController(PriceService service){this.service=service;}
 @GetMapping("/prices") @Operation(summary="Paged candidate reference prices; no scope priority or final price selection")
 public ApiResponse<PageResponse<PriceView>> list(@Valid @ModelAttribute PriceFilter f,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.list(f,page,size));}
 @GetMapping("/prices/{id}") public ApiResponse<PriceView> detail(@PathVariable long id){return ApiResponse.success(service.detail(id));}
 @GetMapping("/records/{id}/versions") public ApiResponse<PageResponse<PriceView>> history(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
 @PostMapping("/prices") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<PriceView> create(@Valid @RequestBody PriceRequests.Create r){return ApiResponse.success(service.create(r));}
 @PostMapping("/records/{id}/versions") @PreAuthorize("hasRole('ADMIN')") public ApiResponse<PriceView> version(@PathVariable long id,@Valid @RequestBody PriceRequests.Version r){return ApiResponse.success(service.version(id,r));}
 @RequestMapping(value="/prices/{id}",method={RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE}) @PreAuthorize("hasRole('ADMIN')")
 @Operation(summary="Reject historical mutation; use new version endpoint")
 public ApiResponse<Void> immutable(@PathVariable long id){service.rejectMutation();return ApiResponse.success(null);}
}
