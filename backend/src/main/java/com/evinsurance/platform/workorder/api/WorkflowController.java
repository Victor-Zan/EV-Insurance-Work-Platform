package com.evinsurance.platform.workorder.api;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.workorder.application.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/work-orders/{id}/workflow")
@Tag(name="Work orders")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class WorkflowController {
    private final WorkflowService service;
    public WorkflowController(WorkflowService service){this.service=service;}
    @GetMapping
    @Operation(summary="管理员/客服只读案件流程快照：九节点、当前执行角色、有效材料与缺失项；不代替写接口授权")
    public ApiResponse<WorkflowService.View> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
}
