package com.evinsurance.platform.workorder.api;

import com.evinsurance.platform.workorder.infrastructure.WorkOrderRows;
import java.time.Instant;
import java.util.UUID;

public final class WorkOrderViews {
    private WorkOrderViews() {}

    public record WorkOrder(
        UUID id,
        String businessNo,
        String insuranceCompany,
        String claimNo,
        Instant claimReportedAt,
        String dataSource,
        Long currentResponsibleId,
        String ownerBindingStatus,
        String ownerName,
        String ownerPhone,
        String policyNo,
        String vehicleBrand,
        String vehicleModel,
        String vehicleVin,
        String vehiclePlate,
        String vehicleOtherIdentifier,
        Instant accidentAt,
        Long accidentRegionId,
        String accidentAddress,
        String accidentDescription,
        Long shopId,
        String status,
        Instant statusStartedAt,
        long version,
        long createdBy,
        Instant createdAt,
        Instant updatedAt,
        WorkOrderRows.Assignment currentAssignment) {}

    public record DuplicateConfiguration(int possibleDuplicateDays) {}
}
