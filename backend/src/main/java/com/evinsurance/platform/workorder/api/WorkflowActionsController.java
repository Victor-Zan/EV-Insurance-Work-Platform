package com.evinsurance.platform.workorder.api;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.workorder.application.WorkflowActions;
import com.evinsurance.platform.workorder.application.WorkOrderDeletion;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/work-orders/{id}/management")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
public class WorkflowActionsController {
    public record Outcome(boolean removalPending) {}
    private final WorkflowActions actions;
    private final WorkOrderDeletion deletion;
    public WorkflowActionsController(WorkflowActions actions,WorkOrderDeletion deletion){
        this.actions=actions;this.deletion=deletion;
    }

    @GetMapping
    @Operation(summary="当前负责人、可办理操作及回撤和改派的材料预览")
    public ApiResponse<WorkflowActions.View> view(@PathVariable UUID id){return ApiResponse.success(actions.get(id));}

    @GetMapping("/recipients")
    public ApiResponse<PageResponse<WorkflowActions.Staff>> candidates(@PathVariable UUID id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        return ApiResponse.success(actions.candidates(id,page,size));
    }

    @PostMapping("/transfer")
    public ApiResponse<Void> transfer(@PathVariable UUID id,@RequestBody WorkflowActions.Transfer body){
        actions.transfer(id,body);
        return ApiResponse.success(null);
    }

    @PostMapping("/withdraw")
    @Operation(summary="按服务端当前状态回撤一步，并校验版本和材料范围")
    public ApiResponse<Outcome> withdraw(@PathVariable UUID id,@RequestBody WorkflowActions.Change body){
        return ApiResponse.success(new Outcome(actions.withdraw(id,body)));
    }

    @PostMapping("/reassign")
    @Operation(summary="维修前更换网点，确认车辆交接和材料范围后重新派单")
    public ApiResponse<Outcome> reassign(@PathVariable UUID id,@RequestBody WorkflowActions.Reassignment body,
        @RequestHeader("Idempotency-Key") String key){
        return ApiResponse.success(new Outcome(actions.reassign(id,body,key)));
    }

    @GetMapping("/deletion")
    public ApiResponse<WorkOrderDeletion.View> deletion(@PathVariable UUID id){return ApiResponse.success(deletion.get(id));}

    @PostMapping("/deletion")
    public ApiResponse<Void> requestDeletion(@PathVariable UUID id,@RequestBody WorkOrderDeletion.Change body){
        deletion.submit(id,body);return ApiResponse.success(null);
    }

    @PostMapping("/deletion/cancel")
    public ApiResponse<Void> cancelDeletion(@PathVariable UUID id,@RequestBody WorkOrderDeletion.Change body){
        deletion.cancel(id,body,false);return ApiResponse.success(null);
    }

    @PostMapping("/deletion/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> rejectDeletion(@PathVariable UUID id,@RequestBody WorkOrderDeletion.Change body){
        deletion.cancel(id,body,true);return ApiResponse.success(null);
    }

    @PostMapping("/deletion/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Outcome> approveDeletion(@PathVariable UUID id,@RequestBody WorkOrderDeletion.Approval body){
        return ApiResponse.success(new Outcome(deletion.approve(id,body)));
    }
}
