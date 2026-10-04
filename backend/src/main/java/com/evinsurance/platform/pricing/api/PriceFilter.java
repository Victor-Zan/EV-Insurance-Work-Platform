package com.evinsurance.platform.pricing.api;
import com.evinsurance.platform.pricing.domain.*;
import java.time.LocalDate;
import jakarta.validation.constraints.*;
public record PriceFilter(@Size(max=64) String internalCode,@Size(max=120) String alias,@Positive Long brandId,
 @Positive Long modelId,PriceType priceType,PriceScope scope,@Positive Long regionId,@Positive Long shopId,LocalDate queryDate){}
