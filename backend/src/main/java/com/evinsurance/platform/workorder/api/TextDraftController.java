package com.evinsurance.platform.workorder.api;
import com.evinsurance.platform.workorder.application.TextDraftService;
import com.evinsurance.platform.foundation.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/work-orders/text") @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class TextDraftController {
    private final TextDraftService service;public TextDraftController(TextDraftService service){this.service=service;}
    public record Extract(String text){} public record Create(String text,boolean confirmed,@NotNull @Valid WorkOrderRequests.Draft draft){}
    @PostMapping("/extract") @Operation(summary="粘贴文字标签提取，不调用OCR，不创建工单") public ApiResponse<tools.jackson.databind.JsonNode> extract(@RequestBody Extract body){return ApiResponse.success(service.extract(body.text()));}
    @PostMapping("/draft") @Operation(summary="人工核对文字后创建草稿，原文候选采用值不可变关联；不正式提交") public ApiResponse<WorkOrderViews.WorkOrder> draft(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body){return ApiResponse.success(service.create(key,body.text(),body.confirmed(),body.draft()));}
}
