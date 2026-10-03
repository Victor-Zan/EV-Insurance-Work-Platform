package com.evinsurance.platform.identity.infrastructure;

import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record AccessRow(long id, String username, String displayName, String passwordHash, boolean enabled,
                        long authVersion, String roleCodes, Long shopId) {
    public Set<Role> roles() {
        return roleCodes == null ? Set.of() : Arrays.stream(roleCodes.split(",")).map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
    }
    public CurrentUser current() { return new CurrentUser(id, username, displayName, roles(), shopId, authVersion); }
}
