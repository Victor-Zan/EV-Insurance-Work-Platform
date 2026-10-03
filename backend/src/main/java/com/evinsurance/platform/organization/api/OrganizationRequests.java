package com.evinsurance.platform.organization.api;

import jakarta.validation.constraints.*;
import java.util.Set;

public final class OrganizationRequests {
    private OrganizationRequests() {}
    public record Region(@Positive Long parentId,@NotNull @Min(1) @Max(3) Integer level,
        @NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String code,
        @NotNull Boolean enabled,@NotNull @Min(0) Integer sortOrder) {}
    public record Shop(@NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String code,
        @NotNull Boolean enabled,@Size(max=100) String contactName,@Size(max=32) String contactPhone,@Size(max=255) String address) {}
    public record ServiceRegions(@NotNull @Size(max=100) Set<@NotNull @Positive Long> regionIds) {}
}
