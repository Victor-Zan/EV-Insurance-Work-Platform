package com.evinsurance.platform.identity.domain;

import java.util.Set;

public enum Portal {
    ADMIN(Set.of(Role.ADMIN, Role.CUSTOMER_SERVICE)), H5(Set.of(Role.REPAIR_SHOP, Role.OWNER));
    private final Set<Role> roles;
    Portal(Set<Role> roles) { this.roles = roles; }
    public boolean allows(Set<Role> actual) { return !actual.isEmpty() && roles.containsAll(actual); }
}
