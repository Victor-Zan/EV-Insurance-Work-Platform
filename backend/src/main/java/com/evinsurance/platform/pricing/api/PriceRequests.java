package com.evinsurance.platform.pricing.api;
import com.evinsurance.platform.pricing.domain.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
public final class PriceRequests {
 private PriceRequests(){}
 public record Create(@NotNull @Positive Long partId,@NotNull @Positive Long modelId,@NotNull PriceType priceType,
  @NotNull PriceScope scope,Long regionId,Long shopId,@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
  @NotNull @Positive Long sourceId,@NotNull LocalDate effectiveFrom,LocalDate effectiveTo){}
 public record Version(@NotNull @DecimalMin("0.01") @Digits(integer=16,fraction=2) BigDecimal amount,
  @NotNull @Positive Long sourceId,@NotNull LocalDate effectiveFrom,LocalDate effectiveTo){}
}
