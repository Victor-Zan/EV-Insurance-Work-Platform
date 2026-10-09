package com.evinsurance.platform.ocr.infrastructure;
import java.util.UUID;
import java.time.Instant;
public record OcrRow(UUID id,UUID fileId,String provider,String state,int attempts,boolean simulateFailure,
    UUID leaseToken,Instant leaseUntil,String errorCode,String candidateJson,int reviewVersion,long createdBy,Instant createdAt,Instant updatedAt) {}
