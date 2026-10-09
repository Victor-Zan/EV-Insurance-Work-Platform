package com.evinsurance.platform.quotation.api;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
public final class QuotationRequests {
    private QuotationRequests() {}
    public record Line(String description,JsonNode quantity,JsonNode unitPrice) {}
    public record Raw(Integer expectedVersion,Integer assignmentVersion,List<Line> lines) {}
    public record Formal(Integer expectedVersion,UUID rawQuoteId,String mode,JsonNode fixedAmount,JsonNode percentage) {}
    public record Assessment(Integer expectedVersion,JsonNode amount) {}
    public record Confirm(Integer expectedVersion) {}
    public record Start(Integer expectedVersion,Integer assignmentVersion) {}
}
