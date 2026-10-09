package com.evinsurance.platform.quotation.infrastructure;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public final class QuotationRows {
    private QuotationRows() {}
    public static class Context {
        public UUID workOrderId,rawQuoteId,formalQuoteId,assessmentId;
        public int version,basisVersion;
        public boolean insurerConfirmed,serviceConfirmed,authorized;
    }
    public record Raw(UUID id,UUID workOrderId,int versionNo,long shopId,int assignmentVersion,String currency,BigDecimal total,long createdBy,Instant createdAt) {}
    public record RawItem(int lineNo,String description,int quantity,BigDecimal unitPrice,BigDecimal amount,String sourceSnapshot) {}
    public record Formal(UUID id,UUID workOrderId,int versionNo,UUID rawQuoteId,String currency,String markupMode,BigDecimal markupValue,BigDecimal originalTotal,BigDecimal markupAmount,BigDecimal total,String calculationVersion,long createdBy,Instant createdAt) {}
    public record FormalItem(int lineNo,String description,int quantity,BigDecimal originalUnitPrice,BigDecimal originalAmount,BigDecimal allocatedMarkup,BigDecimal externalUnitPrice,BigDecimal externalAmount) {}
    public record Assessment(UUID id,UUID workOrderId,int versionNo,UUID sourceFormalId,BigDecimal amount,String fileSnapshot,long createdBy,Instant createdAt) {}
}
