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
    @PostMapping public ApiResponse<OcrService.View> create(@RequestBody Create body){if(body.fileId()==null)throw ApiException.invalid("fileId required");return ApiResponse.success(service.create(body.fileId(),body.simulateFailure()));}
    @GetMapping("/{id}") public ApiResponse<OcrService.View> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @PostMapping("/{id}/retry") public ApiResponse<OcrService.View> retry(@PathVariable UUID id){return ApiResponse.success(service.retry(id));}
    @PostMapping("/{id}/review") public ApiResponse<OcrService.View> review(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody Revision body){return ApiResponse.success(service.review(id,body.expectedVersion(),body.candidate(),key));}
    @PostMapping("/{id}/confirm") public ApiResponse<OcrService.View> confirm(@PathVariable UUID id,@RequestBody Confirm body){return ApiResponse.success(service.confirm(id,body.expectedVersion()));}
    @GetMapping("/{id}/history") public ApiResponse<PageResponse<Map<String,Object>>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
}
