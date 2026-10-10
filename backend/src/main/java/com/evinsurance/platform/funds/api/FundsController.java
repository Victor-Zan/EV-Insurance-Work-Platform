package com.evinsurance.platform.funds.api;
import com.evinsurance.platform.funds.application.FundsService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequestMapping("/api/v1/funds/cases/{id}") @SecurityRequirement(name="bearerAuth")
public class FundsController {
    private final FundsService service;public FundsController(FundsService service){this.service=service;}
    @GetMapping @Operation(summary="独立资金状态；车主拒绝，网点仅本次派单结算") public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @PostMapping("/targets") public ApiResponse<JsonNode> target(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody FundsRequests.Target body){return ApiResponse.success(service.setTarget(id,body,key));}
    @PostMapping("/entries") public ApiResponse<JsonNode> entry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody FundsRequests.Entry body){return ApiResponse.success(service.record(id,body,key));}
    @PostMapping("/entries/{entry}/reverse") public ApiResponse<JsonNode> reverse(@PathVariable UUID id,@PathVariable UUID entry,@RequestHeader("Idempotency-Key") String key,@RequestBody FundsRequests.Reversal body){return ApiResponse.success(service.reverse(id,entry,body,key));}
    @GetMapping("/entries") public ApiResponse<PageResponse<JsonNode>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
    @GetMapping("/targets") public ApiResponse<PageResponse<JsonNode>> targets(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.targetHistory(id,page,size));}
    @GetMapping("/export") public ResponseEntity<String> export(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int size){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ledger-"+id+".csv").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.export(id,page,size));}
}
