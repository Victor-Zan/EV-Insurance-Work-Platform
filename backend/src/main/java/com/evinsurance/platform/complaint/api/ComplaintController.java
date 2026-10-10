package com.evinsurance.platform.complaint.api;
import com.evinsurance.platform.complaint.application.ComplaintService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/complaints") @SecurityRequirement(name="bearerAuth")
public class ComplaintController {
    private final ComplaintService service;public ComplaintController(ComplaintService service){this.service=service;}
    @GetMapping("/cases/{caseId}") public ApiResponse<PageResponse<JsonNode>> list(@PathVariable UUID caseId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.list(caseId,page,size));}
    @PostMapping("/cases/{caseId}") @Operation(summary="绑定车主开修后创建独立投诉；完成可提交，取消拒绝；不改变维修状态或评分")
    public ApiResponse<JsonNode> create(@PathVariable UUID caseId,@RequestHeader("Idempotency-Key") String key,@RequestBody ComplaintRequests.Create body){return ApiResponse.success(service.create(caseId,body,key));}
    @GetMapping("/{id}") public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @GetMapping("/{id}/history") @Operation(summary="稳定分页投诉处理历史，外部角色移除管理员内部说明")
    public ApiResponse<PageResponse<JsonNode>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
    @PostMapping("/{id}/handle") public ApiResponse<JsonNode> handle(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ComplaintRequests.Handle body){return ApiResponse.success(service.handle(id,body,key));}
    @PostMapping("/{id}/corrections") public ApiResponse<JsonNode> correct(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ComplaintRequests.Correct body){return ApiResponse.success(service.correct(id,body,key));}
}
