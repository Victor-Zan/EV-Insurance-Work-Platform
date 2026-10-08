package com.evinsurance.platform.workorder.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class WorkOrderRequests {
    private WorkOrderRequests() {}

    public record Draft(
        @Size(max=120) String insuranceCompany,
        @Size(max=100) String claimNo,
        Instant claimReportedAt,
        @Size(max=100) String ownerName,
        @Size(max=32) String ownerPhone,
        @Size(max=100) String policyNo,
        @Size(max=100) String vehicleBrand,
        @Size(max=100) String vehicleModel,
        @Size(max=100) String vehicleVin,
        @Size(max=100) String vehiclePlate,
        @Size(max=100) String vehicleOtherIdentifier,
        Instant accidentAt,
        Long accidentRegionId,
        @Size(max=255) String accidentAddress,
        @Size(max=2000) String accidentDescription) {}

    public record Submit(boolean confirmPossibleDuplicate, @Size(max=500) String duplicateReason) {}

    public record Dispatch(
        @NotNull Long shopId,
        boolean confirmPossibleDuplicate,
        @Size(max=500) String duplicateReason) {}

    public record AssignmentAction(@NotNull Integer assignmentVersion) {}

    public record Reject(
        @NotNull Integer assignmentVersion,
        @NotBlank @Size(max=500) String reason) {}

    public record CancelAssignment(
        @NotNull Integer assignmentVersion,
        @NotBlank @Size(max=500) String reason) {}

    public record Reassign(
        @NotNull Long shopId,
        @NotNull Integer assignmentVersion,
        @NotBlank @Size(max=500) String reason,
        boolean vehicleNotArrivedConfirmed,
        @Size(max=1000) String transferDescription,
        boolean confirmPossibleDuplicate) {}

    public record ArrivalException(
        @NotNull Integer assignmentVersion,
        @NotBlank @Size(max=1000) String reason) {}

    public record ContinueWaiting(
        @NotNull Integer assignmentVersion,
        @Size(max=500) String reason) {}

    public record CancelWorkOrder(
        @NotBlank @Size(max=500) String reason,
        @NotBlank @Size(max=1000) String description,
        boolean notifyOwner,
        boolean notifyShop) {}

    public record CriticalFields(
        @NotBlank @Size(max=120) String insuranceCompany,
        @NotBlank @Size(max=100) String claimNo,
        @Size(max=100) String vehicleVin,
        @Size(max=100) String vehiclePlate,
        @Size(max=100) String vehicleOtherIdentifier,
        @NotBlank @Size(max=500) String reason) {}

    public record DuplicateConfiguration(
        @Min(1) @Max(365) int possibleDuplicateDays,
        @NotBlank @Size(max=500) String reason) {}
}
