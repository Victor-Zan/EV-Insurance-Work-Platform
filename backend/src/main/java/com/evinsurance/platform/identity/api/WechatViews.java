package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.identity.infrastructure.AccessRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class WechatViews {
    private WechatViews() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record User(long id, String username, String displayName, boolean enabled, boolean activated,
                       Set<Role> roles, Long shopId) {
        public static User from(AccessRow row) {
            return new User(row.id(),row.username(),row.displayName(),row.enabled(),row.activated(),row.roles(),row.shopId());
        }
    }
    public record LoginResult(String accessToken, Instant expiresAt, User user) {
        @Override public String toString() { return "WechatLoginResult[credential omitted]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Invitation(long userId, String username, boolean alreadyActive, Instant expiresAt,
                             String entryPath, String invitationToken) {
        @Override public String toString() { return "WechatInvitation[credential omitted]"; }
    }
    public record Preview(String displayNameMasked, String usernameMasked, Role role,
                          String shopNameMasked, Instant expiresAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Binding(UUID id, int version, Role role, Long shopId, String status,
                          Instant createdAt, Instant revokedAt) {}
}
