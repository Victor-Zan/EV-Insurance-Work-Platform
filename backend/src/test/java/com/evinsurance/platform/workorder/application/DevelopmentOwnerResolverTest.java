package com.evinsurance.platform.workorder.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevelopmentOwnerResolverTest {
    @Test void nonDevelopmentProfilesIgnoreEvenAnEnabledFlag(){
        for(String profile:new String[]{"default","prod","test"}){
            var env=new MockEnvironment().withProperty("DEV_OWNER_BINDING_ENABLED","true");env.setActiveProfiles(profile);
            var mapper=mock(WorkOrderMapper.class);
            assertThat(new DevelopmentOwnerResolver(env,mapper).resolve()).isNull();verifyNoInteractions(mapper);
        }
    }
    @Test void developmentStillRequiresExplicitOptIn(){
        var env=new MockEnvironment();env.setActiveProfiles("dev");var mapper=mock(WorkOrderMapper.class);
        assertThat(new DevelopmentOwnerResolver(env,mapper).resolve()).isNull();verifyNoInteractions(mapper);
    }
    @Test void optedInDevelopmentUsesTheFixedEligibleAccount(){
        var env=new MockEnvironment().withProperty("DEV_OWNER_BINDING_ENABLED","true");env.setActiveProfiles("dev");
        var mapper=mock(WorkOrderMapper.class);when(mapper.developmentOwner()).thenReturn(4L);
        assertThat(new DevelopmentOwnerResolver(env,mapper).resolve()).isEqualTo(4L);
    }
    @Test void unavailableDevelopmentAccountCannotFallBackToAnotherOwner(){
        var env=new MockEnvironment().withProperty("DEV_OWNER_BINDING_ENABLED","true");env.setActiveProfiles("dev");
        var mapper=mock(WorkOrderMapper.class);when(mapper.developmentOwner()).thenReturn(null);
        assertThatThrownBy(()->new DevelopmentOwnerResolver(env,mapper).resolve()).isInstanceOf(ApiException.class).hasMessageContaining("dev_owner");
        verify(mapper).developmentOwner();verifyNoMoreInteractions(mapper);
    }
}
