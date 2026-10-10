package com.evinsurance.platform.repair.application;

import com.evinsurance.platform.repair.infrastructure.RepairMapper;
import com.evinsurance.platform.foundation.api.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RepairMaterialPolicy {
    private final RepairMapper mapper;
    public RepairMaterialPolicy(RepairMapper mapper){this.mapper=mapper;}
    // Call while holding the work-order lock shared with completion submission.
    public void requireChangeable(UUID id){
        if(mapper.frozen(id))throw ApiException.conflict("COMPLETION_EVIDENCE_FROZEN","Submitted completion photo versions cannot be replaced or voided");
    }
}
