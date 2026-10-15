package com.evinsurance.platform.document.domain;

import java.util.Set;
import com.evinsurance.platform.foundation.api.ApiException;

/** Opening stages only; caller still checks role, assignment and frozen evidence. */
public final class FileStagePolicy {
    private static final Set<String> CLOSED=Set.of("COMPLETED","CANCELLED","CLOSED");
    private static final Set<String> AFTER_ARRIVAL=Set.of("ARRIVED","WAITING_QUOTE","QUOTE_REVIEWING","WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION","REPAIRING","WAITING_OWNER_CONFIRMATION");
    private static final Set<String> QUOTE_EDITABLE=Set.of("ARRIVED","WAITING_QUOTE","QUOTE_REVIEWING","WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION");
    private FileStagePolicy() {}
    public static boolean allowed(String status,FileCategory category,boolean currentFormal) {
        if(CLOSED.contains(status))return false;
        if(!AFTER_ARRIVAL.contains(status)&&!Set.of("DRAFT","PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(status))return false;
        return switch(category) {
            case NOTICE,SCENE_PHOTO -> true;
            case ARRIVAL_PHOTO -> AFTER_ARRIVAL.contains(status)||Set.of("PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(status);
            case SHOP_QUOTE -> QUOTE_EDITABLE.contains(status);
            case ASSESSMENT,LOSS_ASSESSMENT -> QUOTE_EDITABLE.contains(status)&&currentFormal;
            case PROGRESS_PHOTO,COMPLETION_PHOTO -> "REPAIRING".equals(status);
        };
    }
    public static void require(String status,FileCategory category,boolean currentFormal) {
        if(!allowed(status,category,currentFormal))throw ApiException.conflict("MATERIAL_STAGE_CONFLICT","当前步骤尚未开放该材料，或该材料已冻结；请先完成前序工作");
    }
}
