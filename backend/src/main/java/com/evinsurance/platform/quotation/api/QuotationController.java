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
    @PostMapping("/formal-quotes") @Operation(summary="客服审核当前原始版本；可附reviewedLines另存核对修订及审计，网点原价保留，服务端重算加价")
    public ApiResponse<JsonNode> formal(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Formal body){return ApiResponse.success(service.issueFormal(id,body,key));}
    @PostMapping("/assessments") @Operation(summary="客服基于当前正式报价记录核损新版本；未完成报价审核拒绝")
    public ApiResponse<JsonNode> assess(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Assessment body){return ApiResponse.success(service.assess(id,body,key));}
    @PostMapping("/confirmations/{kind}") @Operation(summary="客服依次代录保险确认、执行最终价格确认；最终确认须原件核对、核损版本/文件/采用金额一致并留档")
    public ApiResponse<JsonNode> confirm(@PathVariable UUID id,@PathVariable String kind,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Confirm body){return ApiResponse.success(service.confirm(id,kind,body,key));}
    @PostMapping("/start-repair") @Operation(summary="当前网点开修：锁案件、重新校验四项条件与派单版本；不等回款或网点二次确认")
    public ApiResponse<JsonNode> start(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody QuotationRequests.Start body){return ApiResponse.success(service.start(id,body,key));}
    @GetMapping("/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@PathVariable UUID id,@RequestParam(defaultValue="EVENT") String type,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,type,page,size));}
    @GetMapping("/formal-quotes/{quote}/export") @Operation(summary="客服/管理员导出对外CSV数据，移除内部原价与加价；非正式模板")
    public ResponseEntity<String> export(@PathVariable UUID id,@PathVariable UUID quote){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=quote-"+quote+".csv").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.export(id,quote));}
    @GetMapping("/formal-quotes/{quote}/pdf") @Operation(summary="客服/管理员下载正式报价PDF；仅含对外价格与必要案件信息，保留报价版本并记录审计")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@PathVariable UUID quote){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=quote-"+quote+".pdf").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.pdf(id,quote));}
    @GetMapping("/formal-quotes/{quote}/preview") @Operation(summary="客服/管理员逐页预览同一正式报价PDF；返回PNG及X-Page-Count，page从1开始")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id,@PathVariable UUID quote,@RequestParam(defaultValue="1") int page) throws java.io.IOException {
        var image=service.preview(id,quote,page);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Page-Count",String.valueOf(image.pages()))
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(image.bytes());
    }
}
