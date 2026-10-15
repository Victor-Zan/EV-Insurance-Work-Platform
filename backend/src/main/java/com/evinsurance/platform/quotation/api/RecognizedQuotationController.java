package com.evinsurance.platform.quotation.api;
import com.evinsurance.platform.quotation.application.RecognizedQuotationService;
import com.evinsurance.platform.foundation.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/quotations/cases/{id}")
public class RecognizedQuotationController {
    private final RecognizedQuotationService service;public RecognizedQuotationController(RecognizedQuotationService service){this.service=service;}
    @PostMapping("/recognized-quote") @Operation(summary="当前网点提交已核对原件、识别修订及原始报价，同事务关联") public ApiResponse<tools.jackson.databind.JsonNode> quote(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RecognizedQuotationService.Quote body){return ApiResponse.success(service.quote(id,body,key));}
    @PostMapping("/recognized-assessment") @Operation(summary="客服采用人工确认核损金额并关联识别修订，沿用核损门禁") public ApiResponse<tools.jackson.databind.JsonNode> assess(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RecognizedQuotationService.Assessment body){return ApiResponse.success(service.assess(id,body,key));}
    @GetMapping("/sources/{business}") @Operation(summary="授权查看对应提交版本原件和已确认修订，不混用最新识别") public ApiResponse<tools.jackson.databind.JsonNode> source(@PathVariable UUID id,@PathVariable UUID business){return ApiResponse.success(service.source(id,business));}
}
