package com.evinsurance.platform.ocr.application;

import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.ocr.infrastructure.OcrRow;
import com.evinsurance.platform.quotation.application.QuotationLifecycle;
import com.evinsurance.platform.workorder.infrastructure.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Scope is immutable; current assignment and source must still be valid on every operation. */
@Service
public class ShopQuoteOcrPolicy {
    private final JdbcTemplate db;private final WorkOrderMapper orders;private final FileMapper files;
    public ShopQuoteOcrPolicy(JdbcTemplate db,WorkOrderMapper orders,FileMapper files){this.db=db;this.orders=orders;this.files=files;}
    public Map<String,Object> scope(UUID job){var rows=db.queryForList("SELECT * FROM shop_quote_ocr_scope WHERE job_id=?",job);return rows.isEmpty()?null:rows.getFirst();}
    public WorkOrderEntity shopCase(UUID id,Integer version,boolean lock){
        var actor=CurrentUser.require();if(!actor.roles().contains(Role.REPAIR_SHOP)||actor.shopId()==null)throw ApiException.denied();
        var order=lock?orders.lock(id):orders.find(id);if(order==null)throw ApiException.missing("Work order");
        var assignment=orders.activeAssignment(id);
        if(!actor.shopId().equals(order.getShopId())||assignment==null||!actor.shopId().equals(assignment.getShopId())||!"ACCEPTED".equals(assignment.getStatus())||!Objects.equals(version,assignment.getAssignmentVersion()))throw ApiException.denied();
        if(!QuotationLifecycle.EDITABLE.contains(order.getStatus()))throw ApiException.conflict("QUOTE_READ_ONLY","当前案件不允许报价识别或采用旧结果");return order;
    }
    public void authorize(OcrRow job,boolean write){
        var scope=scope(job.id());if(scope==null)throw ApiException.denied();var actor=CurrentUser.require();
        boolean staff=actor.roles().contains(Role.ADMIN)||actor.roles().contains(Role.CUSTOMER_SERVICE);
        if(staff){if(write)throw ApiException.denied();return;}
        shopCase((UUID)scope.get("work_order_id"),((Number)scope.get("assignment_version")).intValue(),write);
        if(!actor.shopId().equals(((Number)scope.get("shop_id")).longValue())||!sourceValid(job,scope))throw ApiException.denied();
    }
    private boolean sourceValid(OcrRow job,Map<String,Object> scope){
        var f=files.find(job.fileId());return f!=null&&f.workOrderId().equals(scope.get("work_order_id"))&&"ACTIVE".equals(f.state())&&scope.get("category").equals(f.category())&&scope.get("file_sha256").equals(f.sha256())&&Objects.equals(f.shopId(),((Number)scope.get("shop_id")).longValue())&&Objects.equals(f.assignmentVersion(),((Number)scope.get("assignment_version")).intValue());
    }
    public boolean eligible(OcrRow job,boolean lock){
        var scope=scope(job.id());if(scope==null)return false;UUID caseId=(UUID)scope.get("work_order_id");
        var order=lock?orders.lock(caseId):orders.find(caseId);if(order==null||!QuotationLifecycle.EDITABLE.contains(order.getStatus()))return false;
        var assignment=orders.activeAssignment(caseId);long shop=((Number)scope.get("shop_id")).longValue();
        if(!Objects.equals(order.getShopId(),shop)||assignment==null||!Objects.equals(assignment.getShopId(),shop)||!"ACCEPTED".equals(assignment.getStatus())||assignment.getAssignmentVersion()!=((Number)scope.get("assignment_version")).intValue())return false;
        if(lock)db.queryForList("SELECT id FROM app_user WHERE id=? FOR SHARE",job.createdBy());
        return sourceValid(job,scope)&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id JOIN shop_account sa ON sa.user_id=u.id WHERE u.id=? AND u.enabled AND r.code='REPAIR_SHOP' AND sa.shop_id=?)",Boolean.class,job.createdBy(),shop));
    }
}
