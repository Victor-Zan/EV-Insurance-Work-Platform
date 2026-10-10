package com.evinsurance.platform.funds.api;
import com.evinsurance.platform.funds.application.FundsImportService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
@RestController @RequestMapping("/api/v1/funds/imports") @SecurityRequirement(name="bearerAuth")
public class FundsImportController {
    private final FundsImportService service;public FundsImportController(FundsImportService service){this.service=service;}
    @PostMapping public ApiResponse<JsonNode> preview(@RequestParam MultipartFile file){return ApiResponse.success(service.preview(file));}
    @GetMapping public ApiResponse<PageResponse<JsonNode>> batches(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.batches(page,size));}
    @GetMapping("/{id}") public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @GetMapping("/{id}/rows") public ApiResponse<PageResponse<JsonNode>> rows(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.rows(id,page,size));}
    @PostMapping("/{id}/rows/{number}/resolve") public ApiResponse<JsonNode> resolve(@PathVariable UUID id,@PathVariable int number,@RequestBody FundsRequests.Resolution body){return ApiResponse.success(service.resolve(id,number,body));}
    @PostMapping("/{id}/confirm") public ApiResponse<JsonNode> confirm(@PathVariable UUID id){return ApiResponse.success(service.confirm(id));}
    @GetMapping("/{id}/resolutions") public ApiResponse<PageResponse<JsonNode>> resolutions(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.resolutions(id,page,size));}
    @GetMapping("/{id}/source") public ResponseEntity<byte[]> source(@PathVariable UUID id){return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=import-"+id+".bin").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.source(id));}
}
