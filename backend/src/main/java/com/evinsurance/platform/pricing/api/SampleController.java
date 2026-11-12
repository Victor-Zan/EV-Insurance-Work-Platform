package com.evinsurance.platform.pricing.api;

import com.evinsurance.platform.pricing.application.SampleService;
import com.evinsurance.platform.pricing.application.WorkbookPriceImportService;
import com.evinsurance.platform.foundation.api.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/pricing/samples")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
@Tag(name="配件参考样本")
public class SampleController {
    private final SampleService service;
    private final WorkbookPriceImportService workbook;
    public SampleController(SampleService service, WorkbookPriceImportService workbook) {
        this.service=service;
        this.workbook=workbook;
    }

    @GetMapping @Operation(summary="分页查询原始样本及当前核对结果，from/to按导入日期筛选")
    public ApiResponse<PageResponse<JsonNode>> list(@ModelAttribute SampleRequests.Filter filter,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="false") boolean reference) {
        return ApiResponse.success(service.list(filter,page,size,reference));
    }
    @GetMapping("/groups") @Operation(summary="按车型配件、单位、品牌、地区与价格性质统计已确认样本，from/to按导入日期筛选")
    public ApiResponse<PageResponse<JsonNode>> groups(@ModelAttribute SampleRequests.Filter filter,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.groups(filter,page,size));
    }
    @GetMapping("/{id}") @Operation(summary="查看已确认参考价及同一历史档案来源行的两类报价")
    public ApiResponse<JsonNode> details(@PathVariable UUID id) {return ApiResponse.success(service.referenceDetails(id));}
    @PostMapping @Operation(summary="幂等采集单条市场或肖邦邦原始资料，进入待核对")
    public ApiResponse<JsonNode> capture(@Valid @RequestBody SampleRequests.Capture request) {return ApiResponse.success(service.capture(request));}
    @PostMapping("/imports") @Operation(summary="采集UTF-8 CSV或单工作表XLSX，保留原件；重复文件返回原批次")
    public ApiResponse<JsonNode> upload(@RequestParam MultipartFile file,@RequestParam String sourceKind) {return ApiResponse.success(service.upload(file,sourceKind));}
    @PostMapping("/workbook/preview") @Operation(summary="分页预览工作簿配件报价及排除、异常与重复结果")
    public ApiResponse<WorkbookPriceImportService.Preview> workbookPreview(@RequestParam MultipartFile file,
        @ModelAttribute WorkbookPriceImportService.Options options,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(workbook.preview(file,options,page,size));
    }
    @PostMapping("/workbook/confirm") @Operation(summary="确认当前文件和预览选项，幂等追加可用配件报价")
    public ApiResponse<WorkbookPriceImportService.Result> workbookConfirm(@RequestParam MultipartFile file,
        @ModelAttribute WorkbookPriceImportService.Options options,@RequestParam String fingerprint) {
        return ApiResponse.success(workbook.confirm(file,options,fingerprint));
    }
    @PostMapping("/cases/{caseId}/capture") @Operation(summary="从当前已提交网点报价中采集指定行，待人工确认配件属性")
    public ApiResponse<JsonNode> quote(@PathVariable UUID caseId,@Valid @RequestBody SampleRequests.QuoteCapture request) {return ApiResponse.success(service.captureQuote(caseId,request));}
    @PostMapping("/{id}/reviews") @Operation(summary="按预期版本追加核对、排除或更正记录，确认后参与参考统计")
    public ApiResponse<JsonNode> review(@PathVariable UUID id,@Valid @RequestBody SampleRequests.Review request) {return ApiResponse.success(service.review(id,request));}
    @GetMapping("/{id}/history") @Operation(summary="分页查询不可变核对历史")
    public ApiResponse<PageResponse<JsonNode>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponse.success(service.history(id,page,size));}
    @GetMapping("/batches/{id}/source") @Operation(summary="授权下载价格采集原件并记录审计")
    public ResponseEntity<byte[]> source(@PathVariable UUID id) {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=price-source").body(service.source(id));}
    @GetMapping("/template") @Operation(summary="下载配件样本采集CSV模板，导入日期由系统记录")
    public ResponseEntity<byte[]> template() {return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=price-samples.csv").body(("\uFEFF"+String.join(",",SampleService.FIELDS)+"\r\n").getBytes(StandardCharsets.UTF_8));}
}
