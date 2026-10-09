package com.evinsurance.platform.quotation.application;
import com.evinsurance.platform.quotation.infrastructure.*;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.Context;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderEntity;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Called while the work_order row is locked, in the same transaction as the change. */
@Service
public class QuotationLifecycle {
    public static final Set<String> EDITABLE=Set.of("ARRIVED","WAITING_QUOTE","QUOTE_REVIEWING","WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION");
    private static final Set<String> FROZEN=Set.of("REPAIRING","WAITING_OWNER_CONFIRMATION","WAITING_INSURER_PAYMENT","WAITING_SHOP_SETTLEMENT","COMPLETED","CANCELLED","CLOSED");
    private final QuotationMapper mapper;private final AuditService audit;
    public QuotationLifecycle(QuotationMapper mapper,AuditService audit){this.mapper=mapper;this.audit=audit;}
    public void requireEditable(WorkOrderEntity order){if(!EDITABLE.contains(order.getStatus()))throw ApiException.conflict("QUOTE_READ_ONLY","Quote/assessment changes require an arrived case before repair starts");}
    public void save(Context c){if(mapper.save(c)!=1)throw ApiException.conflict("QUOTE_VERSION_CONFLICT","Quotation context changed; refresh before retrying");c.version++;}
    public void invalidate(Context c,String reason) {
        var actor=CurrentUser.require();
        if(c.authorized){mapper.event(c.workOrderId,c.basisVersion,"AUTHORIZATION_REVOKED",reason,actor.id());audit.record(actor,Action.REPAIR_AUTHORIZATION_REVOKED,"WORK_ORDER",c.workOrderId,"Authorization revoked; reason="+reason);}
        c.basisVersion++;c.insurerConfirmed=false;c.serviceConfirmed=false;c.authorized=false;
        mapper.event(c.workOrderId,c.basisVersion,"CONFIRMATIONS_INVALIDATED",reason,actor.id());
        audit.record(actor,Action.QUOTE_CONFIRMATIONS_INVALIDATED,"WORK_ORDER",c.workOrderId,"New confirmation basis="+c.basisVersion+"; reason="+reason);
    }
    public void beforeMaterialChange(WorkOrderEntity order,FileCategory category){if((category==FileCategory.LOSS_ASSESSMENT||category==FileCategory.SHOP_QUOTE)&&FROZEN.contains(order.getStatus()))throw ApiException.conflict("QUOTE_READ_ONLY","Quote/assessment materials are frozen after repair, completion or cancellation");}
    public void afterMaterialChange(WorkOrderEntity order,FileCategory category){if(category!=FileCategory.LOSS_ASSESSMENT)return;var c=mapper.context(order.getId());if(c==null)return;invalidate(c,"LOSS_MATERIAL_CHANGED");save(c);}
}
