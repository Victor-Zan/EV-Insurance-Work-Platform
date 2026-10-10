package com.evinsurance.platform.repair.domain;

import com.evinsurance.platform.document.infrastructure.FileRow;
import com.evinsurance.platform.foundation.api.ApiException;
import java.util.Objects;
import java.util.UUID;

public final class RepairPhotoPolicy {
    private RepairPhotoPolicy() {}
    public static void require(FileRow file,UUID caseId,long shop,int assignment,String category,boolean shopUploaded) {
        if(file==null || !caseId.equals(file.workOrderId()) || !Objects.equals(file.shopId(),shop)
            || !Objects.equals(file.assignmentVersion(),assignment) || !category.equals(file.category())
            || !"ACTIVE".equals(file.state()) || !shopUploaded
            || !("image/jpeg".equals(file.contentType()) || "image/png".equals(file.contentType()))) {
            throw ApiException.conflict("INVALID_REPAIR_PHOTO","Use active JPG/PNG photos uploaded by this shop for this assignment and category");
        }
    }
}
