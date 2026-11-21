package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.foundation.api.ApiException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public final class WechatRequests {
    private WechatRequests() {}
    private static void unknown() { throw ApiException.invalid("Unexpected request field"); }
    public record Login(@NotBlank @Size(max=512) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
        @Override public String toString() { return "WechatLogin[credential omitted]"; }
    }
    public record Bind(@NotBlank @Size(max=512) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code,
                       @NotBlank @Size(max=64) String username,
                       @NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password,
                       @NotNull @AssertTrue Boolean confirmed) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
        @Override public String toString() { return "WechatBind[credentials omitted]"; }
    }
    public record Inspect(@NotBlank @Size(max=43) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
        @Override public String toString() { return "WechatInspect[credential omitted]"; }
    }
    public record Activate(@NotBlank @Size(max=43) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token,
                           @NotBlank @Size(max=512) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code,
                           @NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String password,
                           @NotBlank @Size(max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String confirmPassword,
                           @NotNull @AssertTrue Boolean confirmed) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
        @Override public String toString() { return "WechatActivate[credentials omitted]"; }
    }
    public record OwnerInvitation(@NotNull @PositiveOrZero Long expectedVersion, @NotNull @AssertTrue Boolean verified,
                                  @NotBlank @Size(max=2000) String reason, @Positive Long existingOwnerId) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
    }
    public record Revoke(@NotNull @PositiveOrZero Integer expectedVersion, @NotNull @AssertTrue Boolean verified,
                         @NotBlank @Size(max=2000) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { WechatRequests.unknown(); }
    }
}
