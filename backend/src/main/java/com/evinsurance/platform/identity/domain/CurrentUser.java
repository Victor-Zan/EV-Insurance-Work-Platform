package com.evinsurance.platform.identity.domain;

import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import com.evinsurance.platform.foundation.api.ApiException;

public record CurrentUser(long id, String username, String displayName, Set<Role> roles, Long shopId, long authVersion) {
    public static CurrentUser require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            throw ApiException.denied();
        }
        return user;
    }
    public void requireAdmin() { if (!roles.contains(Role.ADMIN)) throw ApiException.denied(); }
    public void requireShop(long targetShopId) {
        if (!roles.contains(Role.REPAIR_SHOP) || shopId == null || shopId != targetShopId) throw ApiException.denied();
    }
    public void requireOwner(long targetUserId) {
        if (!roles.contains(Role.OWNER) || id != targetUserId) throw ApiException.denied();
    }
}
