package com.evinsurance.platform.document.api;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.foundation.api.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/api/v1/materials") @SecurityRequirement(name="bearerAuth")
public class FileController {
    private final FileService service;
    public FileController(FileService service){this.service=service;}
    @GetMapping("/cases/{id}") @Operation(summary="按角色和当前派单过滤附件；包含分页和稳定排序")
    public ApiResponse<PageResponse<FileService.View>> list(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.list(id,page,size));}
    @PostMapping(value="/cases/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Operation(summary="真实私有附件上传/客服替换；版本不覆盖；Idempotency-Key必填")
    public ApiResponse<FileService.View> upload(@PathVariable UUID id,@RequestParam FileCategory category,@RequestParam(required=false) UUID replace,
        @RequestParam(required=false) Integer assignmentVersion,@RequestHeader("Idempotency-Key") String key,@RequestPart MultipartFile file){return ApiResponse.success(service.upload(id,category,replace,assignmentVersion,key,file));}
    @GetMapping("/{id}/download") @Operation(summary="鉴权后流式下载；对象键和SHA256不返回客户端")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id){var d=service.download(id);return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(d.metadata().contentType())).contentLength(d.metadata().size())
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(d.metadata().name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store").body(new InputStreamResource(d.stream()));}
    @PostMapping("/{id}/void") public ApiResponse<Void> voidFile(@PathVariable UUID id){service.voidFile(id);return ApiResponse.success(null);}
    @GetMapping("/cases/{id}/notice-status") public ApiResponse<FileService.MaterialStatus> notice(@PathVariable UUID id){return ApiResponse.success(service.materialStatus(id));}
    public record MissingRequest(String reason){}
    @PostMapping("/cases/{id}/missing-notice") public ApiResponse<FileService.MaterialStatus> missing(@PathVariable UUID id,@RequestBody MissingRequest body){return ApiResponse.success(service.missing(id,body.reason()));}
}
