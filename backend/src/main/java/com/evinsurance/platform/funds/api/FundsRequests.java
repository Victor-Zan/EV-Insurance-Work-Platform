package com.evinsurance.platform.funds.api;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
public final class FundsRequests {
    private FundsRequests() {}
    public record Target(Integer expectedVersion,String direction,JsonNode amount,String reason) {}
    public record Entry(Integer expectedVersion,String direction,String transactionNo,JsonNode amount,String occurredAt,String note) {}
    public record Reversal(Integer expectedVersion,String reason) {}
    public record Resolution(Integer expectedVersion,UUID caseId,String reason) {}
}
