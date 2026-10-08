package com.evinsurance.platform.workorder.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkOrderMapper {
    void insertDraft(WorkOrderEntity row);
    int updateDraft(@Param("row") WorkOrderEntity row,@Param("actorId") long actorId,@Param("now") Instant now);
    int deleteDraft(@Param("id") UUID id,@Param("version") long version);
    WorkOrderEntity find(@Param("id") UUID id);
    WorkOrderEntity lock(@Param("id") UUID id);
    long count(@Param("scope") String scope,@Param("scopeId") Long scopeId,@Param("status") String status,@Param("query") String query);
    List<WorkOrderEntity> list(@Param("scope") String scope,@Param("scopeId") Long scopeId,@Param("status") String status,
        @Param("query") String query,@Param("offset") int offset,@Param("size") int size);

    void lockSubmissionGuard();
    int possibleDuplicateDays();
    int updatePossibleDuplicateDays(@Param("days") int days,@Param("actorId") long actorId,@Param("now") Instant now);
    String existingFormalClaim(@Param("excludeId") UUID excludeId,@Param("insuranceCompany") String insuranceCompany,@Param("claimNo") String claimNo);
    List<String> possibleDuplicates(@Param("id") UUID id,@Param("phone") String phone,@Param("policyNo") String policyNo,
        @Param("vin") String vin,@Param("plate") String plate,@Param("otherIdentifier") String otherIdentifier,
        @Param("accidentAt") Instant accidentAt,@Param("days") int days);
    List<String> possibleShopDuplicates(@Param("id") UUID id,@Param("phone") String phone,@Param("shopId") long shopId,
        @Param("accidentAt") Instant accidentAt,@Param("days") int days);
    int nextBusinessSequence(@Param("date") LocalDate date);
    int submit(@Param("id") UUID id,@Param("version") long version,@Param("businessNo") String businessNo,
        @Param("ownerUserId") Long ownerUserId,@Param("binding") String binding,@Param("actorId") long actorId,@Param("now") Instant now);
    int updateCriticalFields(@Param("id") UUID id,@Param("version") long version,@Param("insuranceCompany") String insuranceCompany,
        @Param("claimNo") String claimNo,@Param("vin") String vin,@Param("plate") String plate,@Param("otherIdentifier") String otherIdentifier,
        @Param("actorId") long actorId,@Param("now") Instant now);
    int transition(@Param("id") UUID id,@Param("version") long version,@Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus,@Param("shopId") Long shopId,@Param("clearShop") boolean clearShop,
        @Param("actorId") long actorId,@Param("now") Instant now);
    List<Long> ownersByPhone(@Param("phone") String phone);
    long regionEnabled(@Param("id") long id);
    long shopEligible(@Param("shopId") long shopId,@Param("regionId") long regionId);
    long eligibleShopCount(@Param("regionId") long regionId);
    List<WorkOrderRows.ShopOption> eligibleShops(@Param("regionId") long regionId,@Param("offset") int offset,@Param("size") int size);

    int nextAssignmentVersion(@Param("workOrderId") UUID workOrderId);
    void insertAssignment(@Param("workOrderId") UUID workOrderId,@Param("version") int version,@Param("shopId") long shopId,
        @Param("transferDescription") String transferDescription,@Param("actorId") long actorId,@Param("now") Instant now);
    WorkOrderRows.Assignment activeAssignment(@Param("workOrderId") UUID workOrderId);
    WorkOrderRows.Assignment assignment(@Param("workOrderId") UUID workOrderId,@Param("version") int version);
    int acceptAssignment(@Param("id") long id,@Param("actorId") long actorId,@Param("now") Instant now);
    int rejectAssignment(@Param("id") long id,@Param("actorId") long actorId,@Param("reason") String reason,@Param("now") Instant now);
    int cancelAssignment(@Param("id") long id,@Param("actorId") long actorId,@Param("reason") String reason,@Param("now") Instant now);

    void insertHistory(@Param("workOrderId") UUID workOrderId,@Param("fromStatus") String fromStatus,@Param("toStatus") String toStatus,
        @Param("action") String action,@Param("reason") String reason,@Param("actorId") long actorId,
        @Param("actorRoles") String actorRoles,@Param("assignmentVersion") Integer assignmentVersion,
        @Param("traceId") String traceId,@Param("now") Instant now);
    long historyCount(@Param("workOrderId") UUID workOrderId);
    List<WorkOrderRows.History> history(@Param("workOrderId") UUID workOrderId,@Param("offset") int offset,@Param("size") int size);

    int reserveCommand(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key,
        @Param("workOrderId") UUID workOrderId,@Param("assignmentVersion") Integer assignmentVersion,@Param("now") Instant now);
    WorkOrderRows.Command command(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key);
    int completeCommand(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key,
        @Param("responseJson") String responseJson);
}
