package com.evinsurance.platform.historical.api;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.historical.application.HistoricalCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/api/v1/historical-cases") @SecurityRequirement(name="bearerAuth")
public class HistoricalCaseController {
    private final HistoricalCaseService service;
    public HistoricalCaseController(HistoricalCaseService service){this.service=service;}

    @GetMapping @Operation(summary="客服/管理员分页查询独立只读历史案件；不进入现有工单流程")
    public ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String query){return ApiResponse.success(service.archives(page,size,query));}
    @GetMapping("/{id}") public ApiResponse<JsonNode> detail(@PathVariable UUID id){return ApiResponse.success(service.archive(id));}
    @PostMapping("/imports") @Operation(summary="按历史订单双工作表模板上传并预览，源文件私有保存，不立即归档")
    public ApiResponse<JsonNode> preview(@RequestParam MultipartFile file){return ApiResponse.success(service.preview(file));}
    @GetMapping("/imports") public ApiResponse<PageResponse<JsonNode>> batches(@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size){return ApiResponse.success(service.batches(page,size));}
    @GetMapping("/imports/{id}") public ApiResponse<JsonNode> batch(@PathVariable UUID id){return ApiResponse.success(service.getBatch(id));}
    @GetMapping("/imports/{id}/rows") public ApiResponse<PageResponse<JsonNode>> rows(@PathVariable UUID id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.rows(id,page,size));}
    @PostMapping("/imports/{id}/confirm") @Operation(summary="逐行归档有效记录；仅整行完全相同跳过，保留同报案号不同内容")
    public ApiResponse<JsonNode> confirm(@PathVariable UUID id){return ApiResponse.success(service.confirm(id));}
    @GetMapping("/imports/{id}/source") public ResponseEntity<byte[]> source(@PathVariable UUID id){return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=historical-import-"+id+".xlsx")
        .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.source(id));}
}
