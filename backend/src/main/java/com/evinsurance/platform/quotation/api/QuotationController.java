package com.evinsurance.platform.quotation.api;
import com.evinsurance.platform.quotation.application.QuotationService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/quotations/cases/{id}") @SecurityRequirement(name="bearerAuth")
public class QuotationController {
    private final QuotationService service;public QuotationController(QuotationService service){this.service=service;}
    @GetMapping @Operation(summary="报价当前上下文；网点仅本次原始报价及无金额的授权门禁，车主拒绝")
    public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @PostMapping("/raw-quotes") @Operation(summary="当前已接单网点提交不可变报价；金额为CNY元十进制字符串")
    public ApiResponse<JsonNode> raw(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Raw body){return ApiResponse.success(service.submitRaw(id,body,key));}
    @PostMapping("/formal-quotes") @Operation(summary="客服审核当前原始版本、二选一加价并生成外部明细；不改原价")
    public ApiResponse<JsonNode> formal(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Formal body){return ApiResponse.success(service.issueFormal(id,body,key));}
    @PostMapping("/assessments") public ApiResponse<JsonNode> assess(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Assessment body){return ApiResponse.success(service.assess(id,body,key));}
    @PostMapping("/confirmations/{kind}") public ApiResponse<JsonNode> confirm(@PathVariable UUID id,@PathVariable String kind,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Confirm body){return ApiResponse.success(service.confirm(id,kind,body,key));}
    @PostMapping("/start-repair") @Operation(summary="当前网点开修：锁案件、重新校验四项条件与派单版本；不等回款或网点二次确认")
    public ApiResponse<JsonNode> start(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Start body){return ApiResponse.success(service.start(id,body,key));}
    @GetMapping("/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@PathVariable UUID id,@RequestParam(defaultValue="EVENT") String type,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,type,page,size));}
    @GetMapping("/formal-quotes/{quote}/export") @Operation(summary="客服/管理员导出对外CSV数据，移除内部原价与加价；非正式模板")
    public ResponseEntity<String> export(@PathVariable UUID id,@PathVariable UUID quote){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=quote-"+quote+".csv").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.export(id,quote));}
}
