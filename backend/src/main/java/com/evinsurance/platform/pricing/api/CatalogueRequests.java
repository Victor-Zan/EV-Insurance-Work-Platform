package com.evinsurance.platform.pricing.api;
import jakarta.validation.constraints.*;
public final class CatalogueRequests {
 private CatalogueRequests(){}
 public record Brand(@NotBlank @Size(max=64) String code,@NotBlank @Size(max=120) String name,@NotNull Boolean enabled){}
 public record Model(@NotNull @Positive Long brandId,@NotBlank @Size(max=64) String code,@NotBlank @Size(max=120) String name,@NotNull Boolean enabled){}
 public record Part(@NotBlank @Size(max=64) String internalCode,@NotBlank @Size(max=120) String name,@NotNull Boolean enabled){}
 public record Alias(@NotNull @Positive Long partId,@NotBlank @Size(max=120) String name){}
 public record Source(@NotBlank @Size(max=64) String code,@NotBlank @Size(max=120) String name,
  @NotBlank @Pattern(regexp="MANUAL|CSV|EXCEL|HISTORICAL_CASE") String kind,@NotNull Boolean enabled){}
 public record Relation(@NotNull @Positive Long partId,@NotNull @Positive Long modelId){}
}
