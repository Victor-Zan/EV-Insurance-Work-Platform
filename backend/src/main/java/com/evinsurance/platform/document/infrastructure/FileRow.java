package com.evinsurance.platform.document.infrastructure;
import java.time.Instant;
import java.util.UUID;
public record FileRow(UUID id,UUID workOrderId,UUID groupId,String category,int versionNo,String state,
    String objectKey,String originalName,String contentType,long byteSize,String sha256,Long shopId,
    Integer assignmentVersion,long uploadedBy,Instant createdAt) {}
