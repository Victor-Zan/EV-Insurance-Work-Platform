package com.evinsurance.platform.ocr.api;
import com.evinsurance.platform.ocr.application.OcrService;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import tools.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/ocr") @SecurityRequirement(name="bearerAuth")
public class OcrController {
    private final OcrService service;public OcrController(OcrService service){this.service=service;}
    public record Create(UUID fileId,boolean simulateFailure){} public record Revision(int expectedVersion,JsonNode candidate){} public record Confirm(int expectedVersion){}
    public record DraftRequest(int expectedVersion,@jakarta.validation.Valid @jakarta.validation.constraints.NotNull com.evinsurance.platform.workorder.api.WorkOrderRequests.Draft draft){}
    @GetMapping("/configuration") public ApiResponse<Map<String,Object>> configuration(){return ApiResponse.success(service.configuration());}
    @PostMapping(value="/intake",consumes="multipart/form-data") @io.swagger.v3.oas.annotations.Operation(summary="客服建单前私有图片上传并创建OCR任务；Idempotency-Key必填；不创建工单")
    public ApiResponse<OcrService.View> intake(@RequestHeader("Idempotency-Key") String key,@RequestPart org.springframework.web.multipart.MultipartFile file){return ApiResponse.success(service.intake(key,file));}
    @GetMapping("/{id}/raw") @io.swagger.v3.oas.annotations.Operation(summary="授权读取百度原始返回文本；log_id字符串；不返回对象键")
    public ApiResponse<com.evinsurance.platform.ocr.application.OcrSources.Raw> raw(@PathVariable UUID id){return ApiResponse.success(service.raw(id));}
    @GetMapping("/{id}/program") @io.swagger.v3.oas.annotations.Operation(summary="最新只读程序候选；初始及重新整理历史保留，与人工版本分离")
    public ApiResponse<JsonNode> program(@PathVariable UUID id){return ApiResponse.success(service.program(id));}
    @GetMapping("/{id}/image") public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> image(@PathVariable UUID id){var source=service.source(id);return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(source.contentType())).contentLength(source.size()).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(new org.springframework.core.io.InputStreamResource(service.image(id)));}
    @PostMapping("/{id}/draft") @io.swagger.v3.oas.annotations.Operation(summary="人工确认后调用既有草稿服务；每任务只建一个草稿，不自动正式提交")
    public ApiResponse<com.evinsurance.platform.workorder.api.WorkOrderViews.WorkOrder> draft(@PathVariable UUID id,@jakarta.validation.Valid @RequestBody DraftRequest body){return ApiResponse.success(service.draft(id,body.expectedVersion(),body.draft()));}
    @PostMapping public ApiResponse<OcrService.View> create(@RequestBody Create body){if(body.fileId()==null)throw ApiException.invalid("fileId required");return ApiResponse.success(service.create(body.fileId(),body.simulateFailure()));}
    @GetMapping("/{id}") public ApiResponse<OcrService.View> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @PostMapping("/{id}/retry") public ApiResponse<OcrService.View> retry(@PathVariable UUID id){return ApiResponse.success(service.retry(id));}
    @PostMapping("/{id}/review") public ApiResponse<OcrService.View> review(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Revision body){return ApiResponse.success(service.review(id,body.expectedVersion(),body.candidate(),key));}
    @PostMapping("/{id}/confirm") public ApiResponse<OcrService.View> confirm(@PathVariable UUID id,@RequestBody Confirm body){return ApiResponse.success(service.confirm(id,body.expectedVersion()));}
    @PostMapping("/{id}/reextract") @io.swagger.v3.oas.annotations.Operation(summary="客服从已存百度原文重新提取程序候选；新增版本，不外呼、不确认、不写正式业务")
    public ApiResponse<OcrService.View> reextract(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Confirm body){return ApiResponse.success(service.reextract(id,body.expectedVersion(),key));}
    @GetMapping("/{id}/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
}
