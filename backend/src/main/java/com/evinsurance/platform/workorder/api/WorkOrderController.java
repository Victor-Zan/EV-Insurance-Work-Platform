package com.evinsurance.platform.workorder.api;

import com.evinsurance.platform.foundation.api.ApiResponse;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderRows;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/work-orders")
@Tag(name="Work orders")
@PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER')")
public class WorkOrderController {
    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) { this.service=service; }

    @GetMapping
    @Operation(summary="List work orders within the caller's server-side data scope; excludeCompleted filters completed cases before pagination and counting")
    public ApiResponse<PageResponse<WorkOrderViews.WorkOrder>> list(
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String status,@RequestParam(required=false) String query,
        @RequestParam(defaultValue="false") boolean excludeCompleted) {
        return ApiResponse.success(service.list(page,size,status,query,excludeCompleted));
    }

    @GetMapping("/configuration")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkOrderViews.DuplicateConfiguration> configuration() {
        return ApiResponse.success(service.configuration());
    }

    @PutMapping("/configuration")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkOrderViews.DuplicateConfiguration> updateConfiguration(
        @Valid @RequestBody WorkOrderRequests.DuplicateConfiguration request) {
        return ApiResponse.success(service.updateConfiguration(request));
    }

    @PostMapping("/drafts")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="Create a server-side draft without allocating a formal business number")
    public ApiResponse<WorkOrderViews.WorkOrder> createDraft(@Valid @RequestBody WorkOrderRequests.Draft request) {
        return ApiResponse.success(service.createDraft(request));
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> updateDraft(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Draft request) {
        return ApiResponse.success(service.updateDraft(id,request));
    }

    @DeleteMapping("/{id}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<Void> deleteDraft(@PathVariable UUID id) {
        service.deleteDraft(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="Validate and submit a formal work order; all intake fields and a scene photo are required")
    public ApiResponse<WorkOrderViews.WorkOrder> submit(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Submit request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.submit(id,request,idempotencyKey));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkOrderViews.WorkOrder> detail(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PutMapping("/{id}/critical-fields")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> updateCriticalFields(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.CriticalFields request) {
        return ApiResponse.success(service.updateCriticalFields(id,request));
    }

    @PostMapping("/{id}/owner-binding")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="人工触发待绑定案件的唯一手机号匹配；不转移已有绑定")
    public ApiResponse<WorkOrderViews.WorkOrder> ownerBinding(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.OwnerBinding request,@RequestHeader("Idempotency-Key") String key) { return ApiResponse.success(service.bindOwner(id,request,key)); }

    @GetMapping("/{id}/eligible-shops")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<PageResponse<WorkOrderRows.ShopOption>> eligibleShops(@PathVariable UUID id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.eligibleShops(id,page,size));
    }

    @PutMapping("/{id}/dispatch-location")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="确认车辆当前停放位置；不修改事故区县或事故地址；版本校验与审计")
    public ApiResponse<WorkOrderViews.WorkOrder> dispatchLocation(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.DispatchLocation request){return ApiResponse.success(service.confirmDispatchLocation(id,request));}

    @PutMapping("/{id}/accident-district")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="派单阶段独立人工核对事故区县；必填原因、版本、审计；不修改停车位置")
    public ApiResponse<WorkOrderViews.WorkOrder> accidentDistrict(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.AccidentDistrict request){return ApiResponse.success(service.confirmAccidentDistrict(id,request));}

    @GetMapping("/accident-districts")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    @Operation(summary="启用的完整省市区县树中的区县分页查询；仅供人工核对，不自动展开城市服务关系")
    public ApiResponse<PageResponse<WorkOrderRows.CityOption>> accidentDistricts(@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.accidentDistricts(query,page,size));}

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> dispatch(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Dispatch request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.dispatch(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> reassign(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Reassign request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.reassign(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/cancel-assignment")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> cancelAssignment(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.CancelAssignment request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.cancelAssignment(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('REPAIR_SHOP')")
    public ApiResponse<WorkOrderViews.WorkOrder> accept(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.AssignmentAction request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.accept(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('REPAIR_SHOP')")
    public ApiResponse<WorkOrderViews.WorkOrder> reject(@PathVariable UUID id,@Valid @RequestBody WorkOrderRequests.Reject request,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.reject(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/arrival-exceptions")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP')")
    public ApiResponse<WorkOrderViews.WorkOrder> arrivalException(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.ArrivalException request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.arrivalException(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/continue-waiting")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP')")
    public ApiResponse<WorkOrderViews.WorkOrder> continueWaiting(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.ContinueWaiting request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.continueWaiting(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/arrive")
    @PreAuthorize("hasRole('REPAIR_SHOP')")
    public ApiResponse<WorkOrderViews.WorkOrder> arrive(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.AssignmentAction request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.arrive(id,request,idempotencyKey));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER_SERVICE')")
    public ApiResponse<WorkOrderViews.WorkOrder> cancelWorkOrder(@PathVariable UUID id,
        @Valid @RequestBody WorkOrderRequests.CancelWorkOrder request,@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.success(service.cancelWorkOrder(id,request,idempotencyKey));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<PageResponse<WorkOrderRows.History>> history(@PathVariable UUID id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.history(id,page,size));
    }
}
