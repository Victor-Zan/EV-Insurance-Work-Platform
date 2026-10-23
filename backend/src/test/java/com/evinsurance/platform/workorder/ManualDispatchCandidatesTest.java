package com.evinsurance.platform.workorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.integration.map.MapProvider;
import com.evinsurance.platform.workorder.application.WorkOrderService;
import com.evinsurance.platform.workorder.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class ManualDispatchCandidatesTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void role(Role role){var user=new CurrentUser(1,"synthetic","合成测试",Set.of(role),null,0);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
    @Test void manualCandidatesNeverTouchProviderEvenWhenMockGeocodingWouldFail() {
        var mapper=mock(WorkOrderMapper.class);var maps=mock(MapProvider.class);
        var service=new WorkOrderService(mapper,mock(AuditService.class),new ObjectMapper(),mock(FileMapper.class),maps);
        var id=UUID.randomUUID();var row=new WorkOrderEntity();row.setId(id);row.setStatus("PENDING_DISPATCH");row.setAccidentRegionId(3L);row.setDispatchAddressConfirmed(true);row.setParkingAddress("合成停车地址");
        when(mapper.find(id)).thenReturn(row);when(mapper.eligibleShopCount(3L)).thenReturn(1L);
        var shop=new WorkOrderRows.ShopOption();shop.setId(7L);shop.setName("合成测试网点");when(mapper.eligibleShops(3L,20,20)).thenReturn(List.of(shop));
        for(var role:List.of(Role.ADMIN,Role.CUSTOMER_SERVICE)){role(role);var result=service.eligibleShops(id,2,20,false);assertThat(result.records()).containsExactly(shop);assertThat(shop.getDistanceMetres()).isNull();}
        verifyNoInteractions(maps);
        for(var role:List.of(Role.REPAIR_SHOP,Role.OWNER)){role(role);assertThatThrownBy(()->service.eligibleShops(id,2,20,false)).isInstanceOf(ApiException.class);}
        verify(mapper,times(2)).eligibleShops(3L,20,20);verifyNoInteractions(maps);
    }
    @Test void missingAccidentDistrictDoesNotUseParkingRegionOrInvokeMap() {
        role(Role.CUSTOMER_SERVICE);var mapper=mock(WorkOrderMapper.class);var maps=mock(MapProvider.class);
        var service=new WorkOrderService(mapper,mock(AuditService.class),new ObjectMapper(),mock(FileMapper.class),maps);
        var id=UUID.randomUUID();var row=new WorkOrderEntity();row.setStatus("PENDING_DISPATCH");row.setParkingRegionId(3L);when(mapper.find(id)).thenReturn(row);
        assertThat(service.eligibleShops(id,1,20,false).records()).isEmpty();verifyNoInteractions(maps);verify(mapper,never()).eligibleShopCount(anyLong());
    }
}
