package com.evinsurance.platform.identity.api;

import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.identity.infrastructure.AccessRow;
import java.util.Set;

public record UserView(long id, String username, String displayName, boolean enabled, Set<Role> roles, Long shopId) {
    public static UserView from(AccessRow row) {
        return new UserView(row.id(),row.username(),row.displayName(),row.enabled(),row.roles(),row.shopId());
    }
}
