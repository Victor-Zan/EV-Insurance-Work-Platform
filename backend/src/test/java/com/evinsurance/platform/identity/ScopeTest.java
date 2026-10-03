package com.evinsurance.platform.identity;

import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.foundation.api.ApiException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ScopeTest {
    @Test void shopScopeAndOwnerScopeRequireExactServerSideIdentity() {
        var shop=new CurrentUser(1,"test_shop","测试",Set.of(Role.REPAIR_SHOP),10L,0);
        shop.requireShop(10); assertThatThrownBy(() -> shop.requireShop(11)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> shop.requireOwner(1)).isInstanceOf(ApiException.class);
        var owner=new CurrentUser(2,"test_owner","测试",Set.of(Role.OWNER),null,0);
        owner.requireOwner(2); assertThatThrownBy(() -> owner.requireOwner(3)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> owner.requireShop(10)).isInstanceOf(ApiException.class);
    }
    @Test void fourRolesOnlyUseTheirAllowedPortal() {
        assertThat(Portal.ADMIN.allows(Set.of(Role.ADMIN,Role.REPAIR_SHOP))).isFalse();
        assertThat(Portal.H5.allows(Set.of(Role.ADMIN,Role.REPAIR_SHOP))).isFalse();
        assertThat(Portal.ADMIN.allows(Set.of())).isFalse();
        for (Role role:Role.values()) {
            boolean management=role==Role.ADMIN || role==Role.CUSTOMER_SERVICE;
            assertThat(Portal.ADMIN.allows(Set.of(role))).isEqualTo(management);
            assertThat(Portal.H5.allows(Set.of(role))).isEqualTo(!management);
        }
    }
}
