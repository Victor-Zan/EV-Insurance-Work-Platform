package com.evinsurance.platform.assistant.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssistantOrderReader {
    List<StatusCount> counts(@Param("status") String status, @Param("start") Instant start, @Param("end") Instant end);
    List<OrderFact> orders(@Param("status") String status, @Param("start") Instant start, @Param("end") Instant end,
                           @Param("number") String number);
    AmountTotal confirmedAmount(@Param("start") Instant start, @Param("end") Instant end);
    CashTotal cash(@Param("start") Instant start, @Param("end") Instant end);
    List<RecentFinance> recentFinance(@Param("limit") int limit, @Param("completed") boolean completed,
                                      @Param("start") Instant start, @Param("end") Instant end);
    CaseRow caseDetails(@Param("number") String number);
    List<RecognizedValue> recognizedValues(@Param("number") String number, @Param("kind") String kind);

    record StatusCount(String status, long count) {}
    record OrderFact(String businessNo, String status, Instant updatedAt, long matchedCount) {}
    record AmountTotal(long count, BigDecimal totalCny) {}
    record CashTotal(BigDecimal received, BigDecimal paid) {}
    record RecentFinance(String businessNo, String status, Instant selectedAt, BigDecimal formalQuote, BigDecimal assessment,
                         BigDecimal insurance, BigDecimal payable, BigDecimal profit, BigDecimal received, BigDecimal paid) {}
    record RecognizedValue(String kind, int lineNo, Integer quantity, BigDecimal unitPrice, BigDecimal amount, long matchedCount) {}
    record CaseRow(String status, boolean deletionPending, boolean dispatchAddressConfirmed, boolean scenePhotoPresent,
                   boolean arrivalPhotoPresent, boolean completionPhotoPresent, BigDecimal rawTotal, BigDecimal formalTotal,
                   BigDecimal assessed, boolean insurerConfirmed, boolean serviceConfirmed, boolean authorized,
                   BigDecimal receiveTarget, BigDecimal payTarget, BigDecimal received, BigDecimal paid, BigDecimal profit) {}
}
