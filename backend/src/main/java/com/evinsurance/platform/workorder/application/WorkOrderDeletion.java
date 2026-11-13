package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderEntity;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderDeletion {
    public record Change(Long version,String reason) {}
    public record Approval(Long version,String number) {}
    public record Request(long applicant,String name,String reason,Instant at) {}
    public record Ledger(String direction,long count,BigDecimal total) {}
    public record View(long version,boolean canRequest,boolean canCancel,boolean canReview,String confirmationNumber,
                       Request request,long materials,List<Ledger> ledger) {}

    private static final List<String> CATEGORIES=Arrays.stream(FileCategory.values()).map(Enum::name).toList();
    private final JdbcTemplate db;
    private final WorkOrderMapper orders;
    private final WorkOrderService workOrders;
    private final WorkflowMaterialRemoval removal;
    private final AuditService audit;

    public WorkOrderDeletion(JdbcTemplate db,WorkOrderMapper orders,WorkOrderService workOrders,
        WorkflowMaterialRemoval removal,AuditService audit) {
        this.db=db;this.orders=orders;this.workOrders=workOrders;this.removal=removal;this.audit=audit;
    }

    @Transactional(readOnly=true)
    public View get(UUID id) {
        workOrders.get(id);
        var row=orders.find(id);
        var actor=CurrentUser.require();
        var request=request(id);
        boolean review=request!=null&&actor.roles().contains(Role.ADMIN);
        var ledger=review?db.query("""
            SELECT direction,count(*) AS entries,sum(amount) AS total
            FROM funds_entry WHERE work_order_id=? GROUP BY direction ORDER BY direction DESC
            """,(rs,i)->new Ledger(rs.getString("direction"),rs.getLong("entries"),rs.getBigDecimal("total")),id):List.<Ledger>of();
        return new View(row.getVersion(),canRequest(row,actor),request!=null&&request.applicant()==actor.id(),
            review,number(row),request,review?db.queryForObject("SELECT count(*) FROM case_file WHERE work_order_id=?",Long.class,id):0,ledger);
    }

    @Transactional
    public void submit(UUID id,Change body) {
        var row=lock(id,body.version());
        var actor=CurrentUser.require();
        if(!canRequest(row,actor))throw ApiException.denied();
        db.update("INSERT INTO work_order_deletion_request(work_order_id,requested_by,reason) VALUES(?,?,?)",id,actor.id(),reason(body.reason()));
        freeze(id,true,actor);
        audit.record(actor,AuditService.Action.WORK_ORDER_DELETE_REQUEST,"WORK_ORDER",id,"Deletion requested; case frozen");
    }

    @Transactional
    public void cancel(UUID id,Change body,boolean reject) {
        lock(id,body.version());
        var actor=CurrentUser.require();
        var request=request(id);
        if(request==null)throw ApiException.conflict("DELETE_REQUEST_MISSING","删除申请已变化");
        if(reject?!actor.roles().contains(Role.ADMIN):request.applicant()!=actor.id())throw ApiException.denied();
        var action=reject?AuditService.Action.WORK_ORDER_DELETE_REJECT:AuditService.Action.WORK_ORDER_DELETE_WITHDRAW;
        audit.record(actor,action,"WORK_ORDER",id,reject?reason(body.reason()):"Deletion request withdrawn");
        db.update("DELETE FROM work_order_deletion_request WHERE work_order_id=?",id);
        freeze(id,false,actor);
    }

    @Transactional
    public boolean approve(UUID id,Approval body) {
        var row=lock(id,body.version());
        CurrentUser.require().requireAdmin();
        if(!row.isDeletionPending()||request(id)==null||!number(row).equals(body.number()))
            throw ApiException.invalid("请核对待审批工单并输入完整编号");
        if("COMPLETED".equals(row.getStatus()))throw ApiException.conflict("WORK_ORDER_COMPLETE","已完成工单无法删除");
        if(Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM funds_import_row own JOIN funds_import_row other ON own.import_id=other.import_id
              WHERE (own.case_id=? OR own.entry_id IN (SELECT id FROM funds_entry WHERE work_order_id=?))
                AND other.case_id IS DISTINCT FROM ?
            )
            """,Boolean.class,id,id,id)))
            throw ApiException.conflict("SHARED_FUNDS_IMPORT","该工单的流水原件属于共用导入批次，需先分离原件才能永久删除");
        var materials=removal.preview(id,CATEGORIES);
        db.execute("SET LOCAL app.approved_work_order_deletion='on'");
        db.query("SELECT erase_approved_work_order_records(?)",rs->{},id);
        removal.remove(id,materials);
        db.update("DELETE FROM work_order_deletion_request WHERE work_order_id=?",id);
        db.update("DELETE FROM work_order WHERE id=?",id);
        db.execute("SET LOCAL app.approved_work_order_deletion='off'");
        return removal.pending(id);
    }

    private Request request(UUID id) {
        return db.query("""
            SELECT r.requested_by,u.display_name,r.reason,r.requested_at
            FROM work_order_deletion_request r JOIN app_user u ON u.id=r.requested_by WHERE r.work_order_id=?
            """,(rs,i)->new Request(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()),id)
            .stream().findFirst().orElse(null);
    }

    private WorkOrderEntity lock(UUID id,Long version) {
        workOrders.get(id);
        var row=orders.lock(id);
        if(row==null)throw ApiException.missing("Work order");
        if(!Objects.equals(version,row.getVersion()))
            throw ApiException.conflict("WORK_ORDER_VERSION_CONFLICT","工单已更新，请刷新并重新核对");
        return row;
    }

    private void freeze(UUID id,boolean frozen,CurrentUser actor) {
        db.update("UPDATE work_order SET deletion_pending=?,version=version+1,updated_by=?,updated_at=now() WHERE id=?",frozen,actor.id(),id);
    }

    private boolean canRequest(WorkOrderEntity row,CurrentUser actor) {
        return !Set.of("COMPLETED","CLOSED").contains(row.getStatus())&&WorkOrderWriteAccess.canWrite(row,actor);
    }

    private String number(WorkOrderEntity row){return Objects.toString(row.getBusinessNo(),Objects.toString(row.getRecalledBusinessNo(),row.getId().toString()));}
    private String reason(String value) {
        if(value==null||value.isBlank()||value.length()>1000)throw ApiException.invalid("请填写原因，最多 1000 字");
        return value.trim();
    }
}
