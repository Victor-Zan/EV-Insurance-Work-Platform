package com.evinsurance.platform.document.application;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.workorder.infrastructure.*;
import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.document.infrastructure.FileRow;
import java.util.UUID;
import org.springframework.stereotype.Service;
@Service
public class FileAccess {
    private final WorkOrderMapper orders;
    private final com.evinsurance.platform.document.infrastructure.FileMapper files;
    public FileAccess(WorkOrderMapper orders,com.evinsurance.platform.document.infrastructure.FileMapper files) { this.orders=orders;this.files=files; }
    public boolean staff(CurrentUser a) { return a.roles().contains(Role.ADMIN)||a.roles().contains(Role.CUSTOMER_SERVICE); }
    public void customerService(CurrentUser a) { if(!a.roles().contains(Role.CUSTOMER_SERVICE)) throw ApiException.denied(); }
    public WorkOrderEntity order(UUID id,boolean lock) {
        var a=CurrentUser.require(); var row=lock?orders.lock(id):orders.find(id);
        if(row==null) throw ApiException.missing("Work order");
        if(a.roles().contains(Role.ADMIN)) return row;
        if(a.roles().contains(Role.CUSTOMER_SERVICE) && (!"DRAFT".equals(row.getStatus())||row.getCreatedBy()==a.id())) return row;
        if(!"DRAFT".equals(row.getStatus()) && a.roles().contains(Role.REPAIR_SHOP)&&a.shopId()!=null&&a.shopId().equals(row.getShopId())) return row;
        if(!"DRAFT".equals(row.getStatus()) && a.roles().contains(Role.OWNER)&&row.getOwnerUserId()!=null&&row.getOwnerUserId()==a.id()) return row;
        throw ApiException.denied();
    }
    public void mutable(WorkOrderEntity row) {
        if(java.util.Set.of("CANCELLED","COMPLETED","CLOSED").contains(row.getStatus())) throw ApiException.conflict("READ_ONLY","Cancelled or completed cases are read-only");
    }
    public Integer assignment(UUID id) { var r=orders.activeAssignment(id); return r==null?null:r.getAssignmentVersion(); }
    public void readable(FileRow f) {
        order(f.workOrderId(),false); var a=CurrentUser.require();
        if(staff(a)) return;
        var category=FileCategory.valueOf(f.category());
        if(!"ACTIVE".equals(f.state())){
            if(a.roles().contains(Role.REPAIR_SHOP)&&category==FileCategory.SHOP_QUOTE&&a.shopId().equals(f.shopId())&&java.util.Objects.equals(assignment(f.workOrderId()),f.assignmentVersion())&&files.submittedQuoteSource(f.id(),f.workOrderId(),a.shopId(),f.assignmentVersion()))return;
            throw ApiException.denied();
        }
        if(a.roles().contains(Role.OWNER)&&category.photo()&&category!=FileCategory.SCENE_PHOTO) return;
        if(a.roles().contains(Role.REPAIR_SHOP)&&category.shopVisible()&&a.shopId().equals(f.shopId())&&
            java.util.Objects.equals(assignment(f.workOrderId()),f.assignmentVersion())) return;
        throw ApiException.denied();
    }
}
