package com.evinsurance.platform.pricing.api;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class SampleRequests {
    private SampleRequests() {}
    public record Capture(@NotNull UUID requestId,
        @Pattern(regexp="XIAOBANGBANG|MARKET") @NotNull String sourceKind,
        @NotNull @Size(min=1,max=20) Map<@Size(max=40) String,@Size(max=256) String> values) {}
    public record QuoteCapture(@NotNull UUID quoteId, @Min(1) int lineNumber) {}
    public record Review(@Min(0) int expectedVersion,
        @NotNull @Pattern(regexp="PENDING|CONFIRMED|EXCLUDED") String status,
        Long partId, Long modelId, @Size(max=40) String amount, @Size(max=24) String unit,
        @Size(max=120) String partBrand, @Size(max=120) String region,
        @NotNull @Pattern(regexp="MARKET_RETAIL|SHOP_QUOTED|SUPPLIER_QUOTED|CP_QUOTED|UNCLASSIFIED") String priceNature,
        @NotBlank @Size(max=1000) String reason) {}
    public record Filter(String query, Long modelId, Long partId, String sourceKind,
        String status, String region, LocalDate from, LocalDate to, UUID batchId,
        String unit, String partBrand, String priceNature, Boolean exactGroup, Long brandId) {}
}
