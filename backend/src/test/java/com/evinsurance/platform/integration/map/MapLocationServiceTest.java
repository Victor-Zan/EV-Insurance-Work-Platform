package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.organization.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MapLocationServiceTest {
    private final RegionMapper regions=mock(RegionMapper.class);
    private final MapProvider provider=mock(MapProvider.class);
    private final MapLocationService service=new MapLocationService(provider,regions,mock(com.evinsurance.platform.audit.application.AuditService.class));
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void role(Role role){var user=new CurrentUser(1,"synthetic","合成客服",Set.of(role),null,0);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
    private RegionEntity region(long id,Long parent,int level,String code,String name){var r=new RegionEntity();r.setId(id);r.setParentId(parent);r.setLevel(level);r.setCode(code);r.setName(name);r.setEnabled(true);return r;}
    @Test void onlyExistingCustomerServiceRoleCanUseMapLocationApis(){
        for(var r:List.of(Role.REPAIR_SHOP,Role.OWNER)){role(r);assertThatThrownBy(service::configuration).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class).extracting("code").isEqualTo("FORBIDDEN");assertThatThrownBy(()->service.resolve(3,"合成街道10号")).extracting("code").isEqualTo("FORBIDDEN");assertThatThrownBy(()->service.regions(null,1,20)).extracting("code").isEqualTo("FORBIDDEN");}
        role(Role.ADMIN);assertThatThrownBy(()->service.resolve(3,"synthetic gate")).extracting("code").isEqualTo("FORBIDDEN");verifyNoInteractions(provider,regions);role(Role.CUSTOMER_SERVICE);when(provider.providerId()).thenReturn("AMAP_WEB_V1");assertThat(service.configuration().recommendationsAvailable()).isTrue();assertThat(service.configuration().mock()).isFalse();
    }
    @Test void hierarchyIsVerifiedBeforeAnyExternalLookup(){
        role(Role.CUSTOMER_SERVICE);var province=region(1,null,1,"440000","广东省");var city=region(2,1L,2,"440100","广州市");var district=region(3,2L,3,"440106","天河区");
        when(regions.selectById(3L)).thenReturn(district);when(regions.selectById(2L)).thenReturn(city);when(regions.selectOne(any())).thenReturn(province);
        when(provider.resolve(any())).thenReturn(List.of());service.resolve(3,"合成街道10号");
        verify(provider).resolve(new MapProvider.Address("广州市","440100","天河区","440106","合成街道10号"));
        reset(provider);city.setParentId(99L);assertThatThrownBy(()->service.resolve(3,"合成街道10号")).hasMessageContaining("广东城市");verifyNoInteractions(provider);
        city.setParentId(1L);district.setEnabled(false);assertThatThrownBy(()->service.resolve(3,"合成街道10号")).hasMessageContaining("广东区县");verifyNoInteractions(provider);
    }
    @Test void pageLimitAndNoConfiguredGuangdongNeverCreateCityCentreFallback(){
        role(Role.CUSTOMER_SERVICE);assertThatThrownBy(()->service.regions(null,1,101)).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
        assertThat(service.regions(null,1,20).records()).isEmpty();verifyNoInteractions(provider);
    }
    @Test void shenzhenUniversityProperNameDoesNotMaskForeignAdministrativeAddresses(){
        for(String detail:List.of("龙翔大道2001号香港中文大学（深圳）东门","龙翔大道2001号香港中文大学(深圳)东门"))
            assertThatCode(()->GuangdongAddress.validate(new MapProvider.Address("深圳市","440300","龙岗区","440307",detail))).doesNotThrowAnyException();
        for(String detail:List.of("香港沙田马料水大学道1号","香港中文大学（深圳）旁香港特别行政区","福建省香港中文大学（深圳）"))
            assertThatThrownBy(()->GuangdongAddress.validate(new MapProvider.Address("深圳市","440300","龙岗区","440307",detail))).hasMessageContaining("省外");
    }

    @Test void pointSelectionRequiresRoleHierarchyAndMatchingGuangdongAdministrationBeforeConfirmation(){
        role(Role.OWNER);assertThatThrownBy(()->service.selectedPoint(3,new MapProvider.Point(23,113))).extracting("code").isEqualTo("FORBIDDEN");verifyNoInteractions(provider);
        role(Role.CUSTOMER_SERVICE);var province=region(1,null,1,"440000","广东省");var city=region(2,1L,2,"440100","广州市");var district=region(3,2L,3,"440106","天河区");when(regions.selectById(3L)).thenReturn(district);when(regions.selectById(2L)).thenReturn(city);when(regions.selectOne(any())).thenReturn(province);when(provider.providerId()).thenReturn("AMAP_WEB_V1");var point=new MapProvider.Point(23,113);
        when(provider.locate(point)).thenReturn(new MapProvider.AddressCandidate("合成地图选点",point,"广东省","广州市","天河区","440106","REVERSE","REQUIRES_CONFIRMATION",false));var choice=service.selectedPoint(3,point).getFirst();assertThat(choice.candidate().status()).isEqualTo("REQUIRES_CONFIRMATION");assertThat(service.session(choice.token(),false).confirmed()).isFalse();verify(provider,times(1)).locate(point);
        when(provider.locate(point)).thenReturn(new MapProvider.AddressCandidate("同名外省",point,"湖南省","广州市","天河区","430106","REVERSE","REQUIRES_CONFIRMATION",false));var foreign=service.selectedPoint(3,point).getFirst();assertThat(foreign.candidate().status()).isEqualTo("REGION_MISMATCH");assertThatThrownBy(()->service.confirm(foreign.token())).isInstanceOf(com.evinsurance.platform.foundation.api.ApiException.class);
    }
}
