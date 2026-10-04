package com.evinsurance.platform.pricing.domain;
import java.math.BigDecimal;
import java.time.LocalDate;
public record PriceDraft(int rowNumber,long partId,long modelId,PriceType priceType,PriceScope scope,
 Long regionId,Long shopId,BigDecimal amount,long sourceId,LocalDate effectiveFrom,LocalDate effectiveTo) {
 public String key(){return partId+"|"+modelId+"|"+priceType+"|"+scope+"|"+regionId+"|"+shopId;}
}
