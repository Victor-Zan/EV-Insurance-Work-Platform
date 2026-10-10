package com.evinsurance.platform.repair.api;
import com.evinsurance.platform.repair.application.RepairService;
import com.evinsurance.platform.foundation.api.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/repairs/cases/{id}") @SecurityRequirement(name="bearerAuth")
public class RepairController {
    private final RepairService service;
    public RepairController(RepairService service){this.service=service;}
    @GetMapping @Operation(summary="维修上下文与完工照片快照；案件及字段权限由后端执行")
    public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
    @GetMapping("/progress") public ApiResponse<PageResponse<JsonNode>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.history(id,page,size));}
    @PostMapping("/progress") @Operation(summary="当前已接网点在合法开修后追加进度；不改变维修状态或计时起点")
    public ApiResponse<JsonNode> progress(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Progress body){return ApiResponse.success(service.progress(id,body,key));}
    @PostMapping("/completion") @Operation(summary="至少一张本次派单由网点上传的有效JPG/PNG完工照；快照冻结后进入待收车")
    public ApiResponse<JsonNode> complete(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Complete body){return ApiResponse.success(service.complete(id,body,key));}
    @PostMapping("/receipt") @Operation(summary="绑定车主或客服确认已收车，工单完成；评价评分均非必填，不参与门禁")
    public ApiResponse<JsonNode> receive(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Receive body){return ApiResponse.success(service.receive(id,body,key));}
    @PostMapping("/receipt/withdraw") public ApiResponse<JsonNode> withdraw(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Withdraw body){return ApiResponse.success(service.withdraw(id,body,key));}
    @PostMapping("/review") public ApiResponse<JsonNode> review(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Review body){return ApiResponse.success(service.review(id,body,key,false));}
    @PostMapping("/review/corrections") public ApiResponse<JsonNode> correct(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RepairRequests.Review body){return ApiResponse.success(service.review(id,body,key,true));}
    @GetMapping("/review/history") public ApiResponse<PageResponse<JsonNode>> reviewHistory(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.reviewHistory(id,page,size));}
}
