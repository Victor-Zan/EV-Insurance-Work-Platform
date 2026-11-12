package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.quotation.application.QuotationLifecycle;
import com.evinsurance.platform.quotation.infrastructure.QuotationMapper;
import com.evinsurance.platform.repair.api.RepairRequests;
import com.evinsurance.platform.repair.application.RepairService;
import com.evinsurance.platform.workorder.api.WorkOrderRequests;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderEntity;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowActions {
    public record Action(String label,String target,boolean noRepair,List<WorkflowMaterialRemoval.Material> materials) {}
    public record View(long version,boolean readOnly,String responsibleName,boolean canTransfer,boolean canChangeShop,
                       boolean removalPending,Action withdrawal,List<WorkflowMaterialRemoval.Material> shopMaterials) {}
    public record Change(Long version,String reason,boolean noRepair,List<UUID> materialIds) {}
    public record Transfer(Long version,Long recipient,String reason) {}
    public record Reassignment(Long version,Long shopId,String reason,String transferDescription,boolean confirmPossibleDuplicate,List<UUID> materialIds) {}
    public record Staff(long id,String name) {}
    private record Step(String label,String target,List<String> categories,boolean noRepair) {}

    private static final List<String> SHOP_FILES=List.of("ARRIVAL_PHOTO","SHOP_QUOTE","ASSESSMENT","LOSS_ASSESSMENT");
    private static final List<String> QUOTE_FILES=List.of("SHOP_QUOTE","ASSESSMENT","LOSS_ASSESSMENT");
    private static final Set<String> BEFORE_REPAIR=Set.of("PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION",
        "ARRIVED","WAITING_QUOTE","QUOTE_REVIEWING","WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION");
    private static final String STAFF_FROM="""
        FROM app_user u WHERE u.enabled AND u.activated AND EXISTS(
          SELECT 1 FROM user_role ur JOIN app_role r ON r.id=ur.role_id
          WHERE ur.user_id=u.id AND r.code='CUSTOMER_SERVICE')
        """;

    private final JdbcTemplate db;
    private final WorkOrderMapper orders;
    private final WorkOrderService workOrders;
    private final QuotationMapper quotes;
    private final QuotationLifecycle lifecycle;
    private final RepairService repair;
    private final WorkflowMaterialRemoval removal;
    private final AuditService audit;

    public WorkflowActions(JdbcTemplate db,WorkOrderMapper orders,WorkOrderService workOrders,QuotationMapper quotes,
        QuotationLifecycle lifecycle,RepairService repair,WorkflowMaterialRemoval removal,AuditService audit) {
        this.db=db;this.orders=orders;this.workOrders=workOrders;this.quotes=quotes;this.lifecycle=lifecycle;
        this.repair=repair;this.removal=removal;this.audit=audit;
    }

    @Transactional(readOnly=true)
    public View get(UUID id) {
        workOrders.get(id);
        var order=orders.find(id);
        var actor=CurrentUser.require();
        boolean writable=WorkOrderWriteAccess.canWrite(order,actor);
        var step=writable?step(order,actor):null;
        boolean shop=writable&&canChangeShop(order,actor);
        String name=db.query("SELECT display_name FROM app_user WHERE id=?", (rs,i)->rs.getString(1),order.getCurrentResponsibleId())
            .stream().findFirst().orElse(null);
        return new View(order.getVersion(),!writable,name,writable&&canTransfer(order,actor),shop,removal.pending(id),
            step==null?null:new Action(step.label(),step.target(),step.noRepair(),removal.preview(id,step.categories())),
            shop?removal.preview(id,SHOP_FILES):List.of());
    }

    public PageResponse<Staff> candidates(UUID id,int page,int size) {
        workOrders.get(id);
        var order=orders.find(id);
        if(!canTransfer(order,CurrentUser.require()))throw ApiException.denied();
        int offset=PageResponse.offset(page,size);
        long total=db.queryForObject("SELECT count(*) "+STAFF_FROM+" AND u.id IS DISTINCT FROM ?",Long.class,order.getCurrentResponsibleId());
        var records=db.query("SELECT u.id,u.display_name "+STAFF_FROM+" AND u.id IS DISTINCT FROM ? ORDER BY u.display_name,u.id LIMIT ? OFFSET ?",
            (rs,i)->new Staff(rs.getLong(1),rs.getString(2)),order.getCurrentResponsibleId(),size,offset);
        return new PageResponse<>(page,size,total,records);
    }

    @Transactional
    public void transfer(UUID id,Transfer request) {
        var row=lock(id,request.version());
        var actor=CurrentUser.require();
        if(!canTransfer(row,actor))throw ApiException.denied();
        String reason=reason(request.reason(),1000);
        if(request.recipient()==null||Objects.equals(row.getCurrentResponsibleId(),request.recipient())
            ||db.queryForObject("SELECT count(*) "+STAFF_FROM+" AND u.id=?",Long.class,request.recipient())!=1)
            throw ApiException.invalid("请选择其他已启用的客服");
        db.update("""
            UPDATE work_order SET current_responsible_id=?,responsibility_transferred=true,
            version=version+1,updated_by=?,updated_at=now() WHERE id=?
            """,request.recipient(),actor.id(),id);
        history(row,row.getStatus(),"WORK_ORDER_TRANSFER",reason,actor);
        audit.record(actor,AuditService.Action.WORK_ORDER_TRANSFER,"WORK_ORDER",id,
            "Responsible user changed from "+row.getCurrentResponsibleId()+" to "+request.recipient());
    }

    @Transactional
    public boolean withdraw(UUID id,Change request) {
        var order=lock(id,request.version());
        var actor=CurrentUser.require();
        String reason=reason(request.reason(),1000);
        var step=step(order,actor);
        if(step==null)throw ApiException.conflict("WITHDRAWAL_UNAVAILABLE","当前步骤无法回撤");
        if(step.noRepair()&&!request.noRepair())throw ApiException.invalid("必须确认网点尚未实际施工");
        var materials=checkedMaterials(id,step.categories(),request.materialIds());
        if("WAITING_OWNER_CONFIRMATION".equals(step.target())) {
            int version=repair.get(id).path("version").asInt();
            repair.withdraw(id,new RepairRequests.Withdraw(version,reason),UUID.randomUUID().toString());
            return removal.pending(id);
        }
        if(Set.of("WAITING_INSURER_PAYMENT","WAITING_SHOP_SETTLEMENT").contains(step.target()))
            withdrawFundsConfirmation(order,step.target());
        else {
            clearQuote(order,step.target());
            removal.remove(id,materials);
        }
        if("PENDING_DISPATCH".equals(step.target())) {
            var active=orders.activeAssignment(id);
            if(active==null||orders.cancelAssignment(active.getId(),actor.id(),reason,Instant.now())!=1)
                throw ApiException.conflict("ASSIGNMENT_CHANGED","当前派单已变化");
        }
        if("DRAFT".equals(step.target()))returnToDraft(order,reason,actor);
        else transition(order,step.target(),"WORK_ORDER_WITHDRAW",reason,actor);
        return removal.pending(id);
    }

    @Transactional
    public boolean reassign(UUID id,Reassignment request,String key) {
        var row=lock(id,request.version());
        var actor=CurrentUser.require();
        if(!canChangeShop(row,actor))throw ApiException.conflict("SHOP_CHANGE_UNAVAILABLE","当前工单无法更换网点");
        var assignment=orders.activeAssignment(id);
        if(assignment==null||request.shopId()==null||request.shopId().equals(assignment.getShopId()))
            throw ApiException.invalid("请选择其他符合派单条件的网点");
        String reason=reason(request.reason(),500);
        String handover=reason(request.transferDescription(),1000);
        var materials=checkedMaterials(id,SHOP_FILES,request.materialIds());
        String status=row.getStatus();
        if(!Set.of("PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION","ARRIVED").contains(status))
            transition(row,"ARRIVED","SHOP_CHANGE_RESET",reason,actor);
        clearQuote(row,"ARRIVED");
        removal.remove(id,materials);
        var body=new WorkOrderRequests.Reassign(request.shopId(),assignment.getAssignmentVersion(),reason,
            Set.of("PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(status),handover,request.confirmPossibleDuplicate());
        workOrders.reassign(id,body,key);
        return removal.pending(id);
    }

    private boolean canTransfer(WorkOrderEntity row,CurrentUser actor) {
        return !row.isDeletionPending()&&!Set.of("DRAFT","CANCELLED","CLOSED","COMPLETED").contains(row.getStatus())
            &&(actor.roles().contains(Role.ADMIN)||Objects.equals(row.getCurrentResponsibleId(),actor.id()));
    }

    private boolean canChangeShop(WorkOrderEntity row,CurrentUser actor) {
        if(!BEFORE_REPAIR.contains(row.getStatus())||row.getShopId()==null||startedRepair(row.getId()))return false;
        return actor.roles().contains(Role.ADMIN)||Set.of("PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(row.getStatus());
    }

    private boolean startedRepair(UUID id) {
        return exists("SELECT EXISTS(SELECT 1 FROM work_order_status_history WHERE work_order_id=? AND to_status='REPAIRING')",id);
    }

    private Step step(WorkOrderEntity row,CurrentUser actor) {
        String status=row.getStatus();
        return switch(status) {
            case "PENDING_DISPATCH" -> new Step("撤回工单提交","DRAFT",List.of(),false);
            case "PENDING_ACCEPTANCE","PENDING_ARRIVAL" -> new Step("撤回派单","PENDING_DISPATCH",SHOP_FILES,false);
            case "ARRIVAL_EXCEPTION" -> new Step("撤回到店异常","PENDING_ARRIVAL",List.of(),false);
            case "ARRIVED","WAITING_QUOTE" -> new Step("撤回到店确认","PENDING_ARRIVAL",SHOP_FILES,false);
            case "QUOTE_REVIEWING" -> new Step("撤回报价提交","ARRIVED",QUOTE_FILES,false);
            case "WAITING_INSURER_ASSESSMENT" -> startedRepair(row.getId())?null:new Step("撤回报价审核","QUOTE_REVIEWING",List.of("LOSS_ASSESSMENT"),false);
            case "WAITING_REPAIR_AUTHORIZATION" -> startedRepair(row.getId())?null:new Step("撤回核损确认","WAITING_INSURER_ASSESSMENT",List.of("LOSS_ASSESSMENT"),false);
            case "REPAIRING" -> repairEvidence(row.getId())?null:new Step("撤回核损确认","WAITING_INSURER_ASSESSMENT",List.of("LOSS_ASSESSMENT"),true);
            case "WAITING_INSURER_PAYMENT" -> canWithdrawReceipt(row,actor)?new Step("撤回收车确认","WAITING_OWNER_CONFIRMATION",List.of(),false):null;
            case "WAITING_SHOP_SETTLEMENT" -> new Step("撤回回款确认","WAITING_INSURER_PAYMENT",List.of(),false);
            case "COMPLETED" -> exists("SELECT EXISTS(SELECT 1 FROM funds_context WHERE work_order_id=? AND staged_flow)",row.getId())
                ?new Step("撤回结算确认","WAITING_SHOP_SETTLEMENT",List.of(),false)
                :canWithdrawReceipt(row,actor)?new Step("撤回收车确认","WAITING_OWNER_CONFIRMATION",List.of(),false):null;
            default -> null;
        };
    }

    private boolean canWithdrawReceipt(WorkOrderEntity row,CurrentUser actor) {
        return actor.roles().contains(Role.CUSTOMER_SERVICE)&&exists("""
            SELECT EXISTS(SELECT 1 FROM repair_receipt r WHERE work_order_id=?
              AND NOT EXISTS(SELECT 1 FROM repair_receipt_withdrawal w WHERE w.receipt_id=r.id))
            """,row.getId());
    }

    private boolean repairEvidence(UUID id) {
        return exists("""
            SELECT EXISTS(SELECT 1 FROM repair_progress WHERE work_order_id=?
            UNION ALL SELECT 1 FROM repair_completion WHERE work_order_id=?
            UNION ALL SELECT 1 FROM case_file WHERE work_order_id=? AND category IN ('PROGRESS_PHOTO','COMPLETION_PHOTO'))
            """,id,id,id);
    }

    private boolean exists(String sql,Object... params) {
        return Boolean.TRUE.equals(db.queryForObject(sql,Boolean.class,params));
    }

    private List<WorkflowMaterialRemoval.Material> checkedMaterials(UUID id,List<String> categories,List<UUID> expected) {
        var materials=removal.preview(id,categories);
        var actual=new HashSet<>(materials.stream().map(WorkflowMaterialRemoval.Material::id).toList());
        if(expected==null||!actual.equals(new HashSet<>(expected)))
            throw ApiException.conflict("WORK_ORDER_MATERIALS_CHANGED","材料范围已变化，请刷新后重新核对");
        return materials;
    }

    private WorkOrderEntity lock(UUID id,Long version) {
        workOrders.get(id);
        var order=orders.lock(id);
        if(order==null)throw ApiException.missing("Work order");
        WorkOrderWriteAccess.require(order,CurrentUser.require());
        if(!Objects.equals(version,order.getVersion()))
            throw ApiException.conflict("WORK_ORDER_VERSION_CONFLICT","工单已更新，请刷新并重新核对");
        return order;
    }

    private void clearQuote(WorkOrderEntity order,String target) {
        if("ARRIVAL_EXCEPTION".equals(order.getStatus())&&"PENDING_ARRIVAL".equals(target))return;
        var context=quotes.context(order.getId());
        if(context==null)return;
        lifecycle.invalidate(context,"WORK_ORDER_WITHDRAW");
        context.assessmentId=null;
        if(!"WAITING_INSURER_ASSESSMENT".equals(target))context.formalQuoteId=null;
        if(!Set.of("WAITING_INSURER_ASSESSMENT","QUOTE_REVIEWING").contains(target))context.rawQuoteId=null;
        lifecycle.save(context);
    }

    private void withdrawFundsConfirmation(WorkOrderEntity order,String target) {
        UUID id=order.getId();
        if("WAITING_INSURER_PAYMENT".equals(target)) {
            db.update("""
                UPDATE funds_context SET version=version+1,
                 receive_withdrawal=coalesce((SELECT max(id) FROM funds_confirmation WHERE work_order_id=? AND direction='RECEIVE'),0),
                 pay_withdrawal=coalesce((SELECT max(id) FROM funds_confirmation WHERE work_order_id=? AND direction='PAY'),0)
                 WHERE work_order_id=?
                """,id,id,id);
        } else {
            db.update("""
                UPDATE funds_context SET version=version+1,
                 pay_withdrawal=coalesce((SELECT max(id) FROM funds_confirmation WHERE work_order_id=? AND direction='PAY'),0)
                 WHERE work_order_id=?
                """,id,id);
        }
    }

    private void transition(WorkOrderEntity row,String target,String action,String reason,CurrentUser actor) {
        var now=Instant.now();
        if(orders.transition(row.getId(),row.getVersion(),row.getStatus(),target,row.getShopId(),
            "PENDING_DISPATCH".equals(target),actor.id(),now)!=1)
            throw ApiException.conflict("WORK_ORDER_VERSION_CONFLICT","工单已更新，请刷新并重新核对");
        history(row,target,action,reason,actor);
        audit.record(actor,AuditService.Action.WORK_ORDER_WITHDRAW,"WORK_ORDER",row.getId(),row.getStatus()+" -> "+target);
    }

    private void returnToDraft(WorkOrderEntity row,String reason,CurrentUser actor) {
        db.update("""
            UPDATE work_order SET recalled_business_no=business_no,business_no=NULL,status='DRAFT',
              status_started_at=now(),version=version+1,updated_at=now(),updated_by=?
            WHERE id=?
            """,actor.id(),row.getId());
        history(row,"DRAFT","WORK_ORDER_WITHDRAW",reason,actor);
        audit.record(actor,AuditService.Action.WORK_ORDER_WITHDRAW,"WORK_ORDER",row.getId(),"PENDING_DISPATCH -> DRAFT");
    }

    private void history(WorkOrderEntity row,String target,String action,String reason,CurrentUser actor) {
        var assignment=orders.activeAssignment(row.getId());
        String roles=String.join(",",actor.roles().stream().map(Enum::name).sorted().toList());
        orders.insertHistory(row.getId(),row.getStatus(),target,action,reason,actor.id(),roles,
            assignment==null?null:assignment.getAssignmentVersion(),Objects.toString(TraceContext.currentTraceId(),UUID.randomUUID().toString()),Instant.now());
    }

    private String reason(String text,int length) {
        if(text==null||text.isBlank()||text.length()>length)throw ApiException.invalid("请填写完整的原因或交接信息");
        return text.trim();
    }
}
