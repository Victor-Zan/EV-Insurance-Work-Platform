package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.identity.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.Set;

public final class IdentityRequests {
    private IdentityRequests() {}
    public record Login(@NotBlank @Size(max=64) String username,
        @NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password, @NotNull Portal portal) {}
    public record CreateUser(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{3,64}") String username,
        @NotBlank @Size(max=100) String displayName,
        @NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password,
        @NotEmpty @Size(max=4) Set<@NotNull Role> roles, @Positive Long shopId) {}
    public record Enabled(@NotNull Boolean enabled) {}
    public record PasswordReset(@NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password) {}
    public record Assignment(@NotEmpty @Size(max=4) Set<@NotNull Role> roles, @Positive Long shopId) {}
}
