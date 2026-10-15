package com.evinsurance.platform.ocr.api;
import com.evinsurance.platform.ocr.application.*;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
@RestController @RequestMapping("/api/v1/quotations/cases/{caseId}/ocr") @SecurityRequirement(name="bearerAuth")
public class ShopQuoteOcrController {
    private final ShopQuoteOcrService service;private final OcrService ocr;
    public ShopQuoteOcrController(ShopQuoteOcrService service,OcrService ocr){this.service=service;this.ocr=ocr;}
    public record Apply(int expectedVersion,List<ShopQuoteOcrService.Item> items){}
    @GetMapping("/configuration") public ApiResponse<Map<String,String>> configuration(){return ApiResponse.success(service.configuration());}
    @PostMapping(consumes="multipart/form-data") @Operation(summary="当前已接单网点上传本次报价清单并创建去重OCR任务；不提交报价")
    public ApiResponse<OcrService.View> upload(@PathVariable UUID caseId,@RequestParam int assignmentVersion,@RequestParam(defaultValue="SHOP_QUOTE") String category,@RequestParam(defaultValue="1") int pageNo,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file){return ApiResponse.success(service.upload(caseId,assignmentVersion,category,pageNo,key,file));}
    @GetMapping("/{job}") public ApiResponse<OcrService.View> get(@PathVariable UUID caseId,@PathVariable UUID job){return ApiResponse.success(service.get(caseId,job));}
    @GetMapping("/{job}/program") public ApiResponse<tools.jackson.databind.JsonNode> program(@PathVariable UUID caseId,@PathVariable UUID job){return ApiResponse.success(service.program(caseId,job));}
    @GetMapping("/{job}/raw") public ApiResponse<OcrSources.Raw> raw(@PathVariable UUID caseId,@PathVariable UUID job){return ApiResponse.success(service.raw(caseId,job));}
    @GetMapping("/{job}/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@PathVariable UUID caseId,@PathVariable UUID job,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(caseId,job,page,size));}
    @GetMapping("/{job}/image") public ResponseEntity<InputStreamResource> image(@PathVariable UUID caseId,@PathVariable UUID job){service.check(caseId,job,false);var source=ocr.source(job);return ResponseEntity.ok().contentType(MediaType.parseMediaType(source.contentType())).contentLength(source.size()).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(new InputStreamResource(ocr.image(job)));}
    @PostMapping("/{job}/retry") public ApiResponse<OcrService.View> retry(@PathVariable UUID caseId,@PathVariable UUID job){return ApiResponse.success(service.retry(caseId,job));}
    @PostMapping("/{job}/apply") @Operation(summary="网点保存人工候选并确认快照；应用到当前表单，不自动提交报价")
    public ApiResponse<OcrService.View> apply(@PathVariable UUID caseId,@PathVariable UUID job,@RequestHeader("Idempotency-Key") String key,@RequestBody Apply body){return ApiResponse.success(service.apply(caseId,job,body.expectedVersion(),body.items(),key));}
}
