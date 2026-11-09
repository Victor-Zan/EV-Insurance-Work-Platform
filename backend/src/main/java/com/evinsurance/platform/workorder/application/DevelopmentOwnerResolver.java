package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class DevelopmentOwnerResolver {
    private final Environment environment;
    private final WorkOrderMapper mapper;
    public DevelopmentOwnerResolver(Environment environment,WorkOrderMapper mapper){this.environment=environment;this.mapper=mapper;}
    public Long resolve(){
        if(!environment.acceptsProfiles(Profiles.of("dev"))||!environment.getProperty("DEV_OWNER_BINDING_ENABLED",Boolean.class,false))return null;
        Long owner=mapper.developmentOwner();
        if(owner==null)throw ApiException.conflict("DEVELOPMENT_OWNER_UNAVAILABLE","开发车主账号不可用，请检查dev_owner启用状态与OWNER角色");
        return owner;
    }
}
