package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderEntity;
import java.util.Objects;

public final class WorkOrderWriteAccess {
    private WorkOrderWriteAccess() {}

    public static boolean canWrite(WorkOrderEntity order,CurrentUser actor) {
        return !order.isDeletionPending() && (!order.isResponsibilityTransferred()
            || !actor.roles().contains(Role.CUSTOMER_SERVICE) || actor.roles().contains(Role.ADMIN)
            || Objects.equals(order.getCurrentResponsibleId(),actor.id()));
    }

    public static void require(WorkOrderEntity order,CurrentUser actor) {
        if(order.isDeletionPending())throw ApiException.conflict("WORK_ORDER_FROZEN","删除申请待审批，工单已暂停办理");
        if(!canWrite(order,actor))throw ApiException.denied();
    }
}
