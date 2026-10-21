package com.evinsurance.platform.workorder.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkOrderMapper {
    long dispatchCityCount(@Param("query") String query);
    List<WorkOrderRows.CityOption> dispatchCities(@Param("query") String query,@Param("offset") int offset,@Param("size") int size);
    java.util.List<WorkOrderRows.Phase> phases(@org.apache.ibatis.annotations.Param("ids") java.util.List<java.util.UUID> ids);
    void insertDraft(WorkOrderEntity row);
    int updateDraft(@Param("row") WorkOrderEntity row,@Param("actorId") long actorId,@Param("now") Instant now);
    int deleteDraft(@Param("id") UUID id,@Param("version") long version);
    WorkOrderEntity find(@Param("id") UUID id);
    WorkOrderEntity lock(@Param("id") UUID id);
    long count(@Param("scope") String scope,@Param("scopeId") Long scopeId,@Param("status") String status,@Param("query") String query,@Param("excludeCompleted") boolean excludeCompleted);
    List<WorkOrderEntity> list(@Param("scope") String scope,@Param("scopeId") Long scopeId,@Param("status") String status,
        @Param("query") String query,@Param("excludeCompleted") boolean excludeCompleted,@Param("offset") int offset,@Param("size") int size);

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
    @org.apache.ibatis.annotations.Update("UPDATE work_order SET owner_user_id=#{owner},owner_binding_status='BOUND',version=version+1,updated_by=#{actor},updated_at=#{now} WHERE id=#{id} AND version=#{version} AND owner_binding_status='PENDING' AND status NOT IN ('DRAFT','CANCELLED','COMPLETED','CLOSED')")
    int bindOwner(@Param("id") UUID id,@Param("version") long version,@Param("owner") long owner,@Param("actor") long actor,@Param("now") Instant now);
    @org.apache.ibatis.annotations.Insert("INSERT INTO work_order_cancellation(work_order_id,owner_id,shop_id,assignment_version,notify_owner,notify_shop,public_reason,cancelled_at) VALUES(#{id},#{owner},#{shop},#{assignment},#{notifyOwner},#{notifyShop},#{reason},#{now})")
    void cancellation(@Param("id") UUID id,@Param("owner") Long owner,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("notifyOwner") boolean notifyOwner,@Param("notifyShop") boolean notifyShop,@Param("reason") String reason,@Param("now") Instant now);
    @org.apache.ibatis.annotations.Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))") int lockOwnerBindingCommand(String key);
    @org.apache.ibatis.annotations.Select("SELECT request_hash FROM work_order_command WHERE actor_id=#{actor} AND operation='OWNER_BIND' AND idempotency_key=#{key}") String bindingCommandHash(@Param("actor") long actor,@Param("key") String key);
    @org.apache.ibatis.annotations.Update("UPDATE work_order_command SET request_hash=#{hash} WHERE actor_id=#{actor} AND operation='OWNER_BIND' AND idempotency_key=#{key}") void bindingCommandHashSave(@Param("actor") long actor,@Param("key") String key,@Param("hash") String hash);
    List<Long> ownersByPhone(@Param("phone") String phone);
    long regionEnabled(@Param("id") long id);
    long shopEligible(@Param("shopId") long shopId,@Param("regionId") long regionId);
    long eligibleShopCount(@Param("regionId") long regionId);
    List<WorkOrderRows.ShopOption> eligibleShops(@Param("regionId") long regionId,@Param("offset") int offset,@Param("size") int size);

    @org.apache.ibatis.annotations.Update("UPDATE work_order SET parking_region_id=#{regionId},parking_address=#{address},dispatch_address_confirmed=TRUE,version=version+1,updated_by=#{actor},updated_at=now() WHERE id=#{id} AND version=#{version} AND status IN ('PENDING_DISPATCH','PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION','ARRIVED')")
    int dispatchLocation(@Param("id") UUID id,@Param("version") long version,@Param("regionId") long regionId,@Param("address") String address,@Param("actor") long actor);

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
    List<WorkOrderRows.Milestone> milestones(@Param("workOrderId") UUID workOrderId);
    WorkOrderRows.History latestHistory(@Param("workOrderId") UUID workOrderId);
    List<WorkOrderRows.History> history(@Param("workOrderId") UUID workOrderId,@Param("offset") int offset,@Param("size") int size);

    int reserveCommand(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key,
        @Param("workOrderId") UUID workOrderId,@Param("assignmentVersion") Integer assignmentVersion,@Param("now") Instant now);
    WorkOrderRows.Command command(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key);
    int completeCommand(@Param("actorId") long actorId,@Param("operation") String operation,@Param("key") String key,
        @Param("responseJson") String responseJson);
}
