package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.workorder.api.WorkOrderRequests;
import com.evinsurance.platform.workorder.api.WorkOrderViews;
import com.evinsurance.platform.workorder.domain.WorkOrderStatus;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderEntity;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderRows;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> STAFF=Set.of(Role.ADMIN.name(),Role.CUSTOMER_SERVICE.name());
    private static final Set<String> PRE_ARRIVAL=Set.of(
        WorkOrderStatus.PENDING_ACCEPTANCE.name(),WorkOrderStatus.PENDING_ARRIVAL.name(),WorkOrderStatus.ARRIVAL_EXCEPTION.name());
    private static final Set<String> ARRIVED_OR_LATER=Set.of(
        WorkOrderStatus.ARRIVED.name(),WorkOrderStatus.WAITING_QUOTE.name(),WorkOrderStatus.QUOTE_REVIEWING.name(),
        WorkOrderStatus.WAITING_INSURER_ASSESSMENT.name(),WorkOrderStatus.WAITING_REPAIR_AUTHORIZATION.name(),
        WorkOrderStatus.REPAIRING.name(),WorkOrderStatus.WAITING_OWNER_CONFIRMATION.name(),
        WorkOrderStatus.WAITING_INSURER_PAYMENT.name(),WorkOrderStatus.WAITING_SHOP_SETTLEMENT.name(),WorkOrderStatus.COMPLETED.name());

    private final WorkOrderMapper mapper;
    private final AuditService audit;
    private final ObjectMapper json;

    private final com.evinsurance.platform.document.infrastructure.FileMapper files;
    private final com.evinsurance.platform.integration.map.MapProvider maps;
    private com.evinsurance.platform.organization.application.CaseWorkshopRegistry caseWorkshops;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCaseWorkshops(com.evinsurance.platform.organization.application.CaseWorkshopRegistry registry){caseWorkshops=registry;}
    public WorkOrderService(WorkOrderMapper mapper,AuditService audit,ObjectMapper json,com.evinsurance.platform.document.infrastructure.FileMapper files,com.evinsurance.platform.integration.map.MapProvider maps) {
        this.mapper=mapper;this.files=files;this.maps=maps;
        this.audit=audit;
        this.json=json;
    }

    @Transactional
    public WorkOrderViews.WorkOrder createDraft(WorkOrderRequests.Draft request,String key) {
        var actor=requireStaff();
        // Existing internal text/OCR services retain their own source-specific idempotency.
        if(key==null)return createDraft(request);
        validateKey(key);
        mapper.lockDraftCreationCommand("manual-draft:"+actor.id()+":"+key);
        String hash=com.evinsurance.platform.document.application.FileService.hash(json.writeValueAsString(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing=mapper.command(actor.id(),"CREATE_DRAFT",key);
        if(existing!=null) {
            if(!hash.equals(mapper.draftCommandHash(actor.id(),key)))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","本次建单请求已变化，请先核对原草稿，不要重复创建");
            return get(existing.getWorkOrderId());
        }
        var result=createDraft(request);
        if(mapper.reserveCommand(actor.id(),"CREATE_DRAFT",key,result.id(),null,Instant.now())!=1)throw ApiException.conflict("IDEMPOTENCY_CONFLICT","建单请求冲突，请先核对原草稿");
        mapper.draftCommandHashSave(actor.id(),key,hash);
        if(mapper.completeCommand(actor.id(),"CREATE_DRAFT",key,json.writeValueAsString(result))!=1)throw ApiException.conflict("IDEMPOTENCY_CONFLICT","建单结果尚未确认，请重试原请求");
        return result;
    }

    @Transactional
    public WorkOrderViews.WorkOrder createDraft(WorkOrderRequests.Draft request) {
        var actor=requireStaff();
        var now=Instant.now();
        var row=from(request);
        row.setId(UUID.randomUUID());
        row.setDataSource("MANUAL");
        row.setCurrentResponsibleId(actor.id());
        row.setCreatedBy(actor.id());
        row.setUpdatedBy(actor.id());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insertDraft(row);
        audit.record(actor,Action.WORK_ORDER_DRAFT_CREATE,"WORK_ORDER",row.getId(),"Draft created; source=MANUAL; responsible="+actor.id());
        return view(mapper.find(row.getId()),true,actor);
    }

    @Transactional
    public WorkOrderViews.WorkOrder updateDraft(UUID id,WorkOrderRequests.Draft request) {
        var actor=requireStaff();
        var before=lock(id);
        requireDraftOwner(before,actor);
        var row=from(request);
        row.setId(id);
        row.setVersion(before.getVersion());
        if(mapper.updateDraft(row,actor.id(),Instant.now())!=1) throw state("Draft changed concurrently");
        audit.record(actor,Action.WORK_ORDER_DRAFT_UPDATE,"WORK_ORDER",id,"Draft business fields updated; version "+before.getVersion()+" -> "+(before.getVersion()+1));
        return view(mapper.find(id),true,actor);
    }

    @Transactional
    public void deleteDraft(UUID id) {
        var actor=requireStaff();
        var row=lock(id);
        requireDraftOwner(row,actor);
        mapper.deleteDraftCreationCommands(id);
        if(mapper.deleteDraft(id,row.getVersion())!=1) throw state("Draft changed concurrently");
        audit.record(actor,Action.WORK_ORDER_DRAFT_DELETE,"WORK_ORDER",id,"Draft physically deleted by its creator or administrator");
    }

    @Transactional
    public WorkOrderViews.WorkOrder submit(UUID id,WorkOrderRequests.Submit request,String key) {
        var actor=requireStaff();
        return command(actor,"SUBMIT",key,id,null,() -> {
            var row=lock(id);
            requireDraftOwner(row,actor);
            validateFormal(row);
            mapper.lockSubmissionGuard();
            var duplicates=possibleDuplicates(row);
            String duplicateReason=requireDuplicateDecision(duplicates,request.confirmPossibleDuplicate(),request.duplicateReason());
            Long owner=matchOwner(row.getOwnerPhone());
            String binding=owner==null?"PENDING":"BOUND";
            LocalDate date=LocalDate.now(BUSINESS_ZONE);
            int sequence=mapper.nextBusinessSequence(date);
            String businessNo="EVR-"+date.format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+String.format("%06d",sequence);
            var now=Instant.now();
            if(mapper.submit(id,row.getVersion(),businessNo,owner,binding,actor.id(),now)!=1) throw state("Draft changed concurrently");
            history(id,WorkOrderStatus.DRAFT.name(),WorkOrderStatus.PENDING_DISPATCH.name(),"SUBMIT",duplicateReason,actor,null,now);
            audit.record(actor,Action.WORK_ORDER_SUBMIT,"WORK_ORDER",id,
                "Status DRAFT -> PENDING_DISPATCH; businessNo="+businessNo+"; ownerBinding="+binding+reasonSummary(duplicateReason));
            return mapper.find(id);
        });
    }

    public PageResponse<WorkOrderViews.WorkOrder> list(int page,int size,String status,String query,boolean excludeCompleted) {
        var actor=CurrentUser.require();
        if(status!=null&&!status.isBlank()) parseStatus(status);
        String scope=scope(actor);
        Long scopeId=switch(scope) {
            case "CUSTOMER_SERVICE","OWNER" -> actor.id();
            case "SHOP" -> actor.shopId();
            default -> null;
        };
        int offset=PageResponse.offset(page,size);
        String search=trim(query);
        var entities=mapper.list(scope,scopeId,status,search,excludeCompleted,offset,size);var phases=new java.util.HashMap<UUID,WorkOrderRows.Phase>();if(!entities.isEmpty())for(var phase:mapper.phases(entities.stream().map(WorkOrderEntity::getId).toList()))phases.put(phase.caseId(),phase);
        var rows=entities.stream().map(r -> view(r,false,actor,phases.get(r.getId()))).toList();
        return new PageResponse<>(page,size,mapper.count(scope,scopeId,status,search,excludeCompleted),rows);
    }

    public WorkOrderViews.WorkOrder get(UUID id) {
        var actor=CurrentUser.require();
        return view(requireVisible(id,actor,false),true,actor);
    }

    public WorkOrderViews.DuplicateConfiguration configuration() {
        CurrentUser.require().requireAdmin();
        return new WorkOrderViews.DuplicateConfiguration(mapper.possibleDuplicateDays());
    }

    @Transactional
    public WorkOrderViews.DuplicateConfiguration updateConfiguration(WorkOrderRequests.DuplicateConfiguration request) {
        var actor=CurrentUser.require();
        actor.requireAdmin();
        mapper.lockSubmissionGuard();
        int before=mapper.possibleDuplicateDays();
        mapper.updatePossibleDuplicateDays(request.possibleDuplicateDays(),actor.id(),Instant.now());
        audit.record(actor,Action.WORK_ORDER_CONFIG_UPDATE,"WORK_ORDER_CONFIG",1L,
            "possibleDuplicateDays "+before+" -> "+request.possibleDuplicateDays()+"; reason="+request.reason().trim());
        return new WorkOrderViews.DuplicateConfiguration(request.possibleDuplicateDays());
    }

    public PageResponse<WorkOrderRows.CityOption> dispatchCities(String query,int page,int size) {
        requireStaff();int offset=PageResponse.offset(page,size);String search=query==null?"":query.trim();
        if(search.length()>100)throw ApiException.invalid("城市搜索不能超过100个字符");
        return new PageResponse<>(page,size,mapper.dispatchCityCount(search),mapper.dispatchCities(search,offset,size));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<WorkOrderRows.ShopOption> eligibleShops(UUID id,int page,int size) {
        return eligibleShops(id,page,size,true);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<WorkOrderRows.ShopOption> eligibleShops(UUID id,int page,int size,boolean includeMockDistances) {
        var actor=requireStaff();
        var row=requireVisible(id,actor,false);
        int offset=PageResponse.offset(page,size);
        if(row.getAccidentRegionId()==null)return new PageResponse<>(page,size,0,List.of());
        if(caseWorkshops!=null&&caseWorkshops.enabled())return caseWorkshops.eligible(id,row.getAccidentRegionId(),page,size);
        long count=mapper.eligibleShopCount(row.getAccidentRegionId());
        if(!includeMockDistances||!"MOCK_V1".equals(maps.providerId())||!row.isDispatchAddressConfirmed()||(trim(row.getParkingAddress())==null&&trim(row.getAccidentAddress())==null))return new PageResponse<>(page,size,count,mapper.eligibleShops(row.getAccidentRegionId(),offset,size));
        // Mock geocoding is local. Read candidates in bounded batches, rank before pagination.
        var ranked=new java.util.ArrayList<WorkOrderRows.ShopOption>();var origin=maps.geocode(row.getParkingAddress()!=null?row.getParkingAddress():row.getAccidentAddress()).point();
        for(int start=0;start<count;start+=100){var batch=mapper.eligibleShops(row.getAccidentRegionId(),start,100);for(var shop:batch){if(trim(shop.getAddress())!=null)shop.setDistanceMetres(maps.distance(origin,maps.geocode(shop.getAddress()).point()).metres());ranked.add(shop);}}
        ranked.sort(java.util.Comparator.comparing(WorkOrderRows.ShopOption::getDistanceMetres,java.util.Comparator.nullsLast(Double::compareTo)).thenComparing(WorkOrderRows.ShopOption::getId));
        return new PageResponse<>(page,size,count,ranked.stream().skip(offset).limit(size).toList());
    }

    @Transactional
    public WorkOrderViews.WorkOrder updateCriticalFields(UUID id,WorkOrderRequests.CriticalFields request) {
        var actor=requireStaff();
        var row=lock(id);
        requireVisibleRow(row,actor);
        if(Set.of(WorkOrderStatus.DRAFT.name(),WorkOrderStatus.CANCELLED.name(),WorkOrderStatus.CLOSED.name(),WorkOrderStatus.COMPLETED.name()).contains(row.getStatus())) {
            throw state("Critical fields cannot be changed in the current state");
        }
        String insurance=trim(request.insuranceCompany());
        String claim=required(request.claimNo(),"claimNo");
        String vin=trim(request.vehicleVin());
        String plate=trim(request.vehiclePlate());
        String other=trim(request.vehicleOtherIdentifier());
        requireVehicleIdentifier(vin,plate,other);
        String before="insuranceCompany="+row.getInsuranceCompany()+",claimNo="+row.getClaimNo()+",vin="+maskIdentifier(row.getVehicleVin())+
            ",plate="+maskIdentifier(row.getVehiclePlate())+",other="+maskIdentifier(row.getVehicleOtherIdentifier());
        String after="insuranceCompany="+insurance+",claimNo="+claim+",vin="+maskIdentifier(vin)+",plate="+maskIdentifier(plate)+",other="+maskIdentifier(other);
        if(mapper.updateCriticalFields(id,row.getVersion(),insurance,claim,vin,plate,other,actor.id(),Instant.now())!=1) {
            throw state("Work order changed concurrently");
        }
        audit.record(actor,Action.WORK_ORDER_CRITICAL_UPDATE,"WORK_ORDER",id,
            "before={"+before+"}; after={"+after+"}; reason="+request.reason().trim());
        return view(mapper.find(id),true,actor);
    }

    @Transactional
    public WorkOrderViews.WorkOrder dispatch(UUID id,WorkOrderRequests.Dispatch request,String key) {
        var actor=requireStaff();
        return command(actor,"DISPATCH",key,id,null,() -> dispatchInternal(id,request.shopId(),actor,"DISPATCH",null,
            request.confirmPossibleDuplicate(),request.duplicateReason(),null));
    }

    @Transactional
    public WorkOrderViews.WorkOrder reassign(UUID id,WorkOrderRequests.Reassign request,String key) {
        var actor=requireStaff();
        return command(actor,"REASSIGN",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            requireVisibleRow(row,actor);
            if(!Set.of(WorkOrderStatus.PENDING_ACCEPTANCE.name(),WorkOrderStatus.PENDING_ARRIVAL.name(),
                WorkOrderStatus.ARRIVAL_EXCEPTION.name(),WorkOrderStatus.ARRIVED.name()).contains(row.getStatus())) {
                throw state("Work order cannot be reassigned now");
            }
            if(Set.of(WorkOrderStatus.PENDING_ARRIVAL.name(),WorkOrderStatus.ARRIVAL_EXCEPTION.name()).contains(row.getStatus())
                && !request.vehicleNotArrivedConfirmed()) {
                throw ApiException.invalid("vehicleNotArrivedConfirmed is required after acceptance and before arrival");
            }
            String transfer=trim(request.transferDescription());
            if(WorkOrderStatus.ARRIVED.name().equals(row.getStatus())) {
                if(!actor.roles().contains(Role.ADMIN)) throw ApiException.denied();
                if(transfer==null) throw ApiException.invalid("transferDescription is required for post-arrival administrator reassignment");
            }
            var assignment=requireCurrentAssignment(row,request.assignmentVersion());
            var now=Instant.now();
            if(mapper.cancelAssignment(assignment.getId(),actor.id(),request.reason().trim(),now)!=1) throw state("Assignment changed concurrently");
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_DISPATCH.name(),null,true,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            var dispatched=dispatchInternal(id,request.shopId(),actor,"REASSIGN",request.reason().trim(),
                request.confirmPossibleDuplicate(),request.reason(),transfer);
            audit.record(actor,Action.WORK_ORDER_REASSIGN,"WORK_ORDER",id,
                "Assignment version "+assignment.getAssignmentVersion()+" cancelled and replaced; reason="+request.reason().trim());
            return dispatched;
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder cancelAssignment(UUID id,WorkOrderRequests.CancelAssignment request,String key) {
        var actor=requireStaff();
        return command(actor,"CANCEL_ASSIGNMENT",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            requireVisibleRow(row,actor);
            if(!PRE_ARRIVAL.contains(row.getStatus())) throw state("Assignment cannot be cancelled now");
            var assignment=requireCurrentAssignment(row,request.assignmentVersion());
            var now=Instant.now();
            if(mapper.cancelAssignment(assignment.getId(),actor.id(),request.reason().trim(),now)!=1) throw state("Assignment changed concurrently");
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_DISPATCH.name(),null,true,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            history(id,row.getStatus(),WorkOrderStatus.PENDING_DISPATCH.name(),"CANCEL_ASSIGNMENT",request.reason().trim(),actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_ASSIGNMENT_CANCEL,"WORK_ORDER",id,
                "Status "+row.getStatus()+" -> PENDING_DISPATCH; assignmentVersion="+request.assignmentVersion()+"; reason="+request.reason().trim());
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder accept(UUID id,WorkOrderRequests.AssignmentAction request,String key) {
        var actor=requireShop();
        requireVisible(id,actor,false);
        return command(actor,"ACCEPT",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            var assignment=requireShopAssignment(row,request.assignmentVersion(),actor);
            if("ACCEPTED".equals(assignment.getStatus()) && !WorkOrderStatus.PENDING_ACCEPTANCE.name().equals(row.getStatus())) return row;
            requireStatus(row,WorkOrderStatus.PENDING_ACCEPTANCE);
            var now=Instant.now();
            if(mapper.acceptAssignment(assignment.getId(),actor.id(),now)!=1) throw state("Assignment changed concurrently");
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_ARRIVAL.name(),row.getShopId(),false,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            history(id,row.getStatus(),WorkOrderStatus.PENDING_ARRIVAL.name(),"ACCEPT",null,actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_ACCEPT,"WORK_ORDER",id,
                "Status PENDING_ACCEPTANCE -> PENDING_ARRIVAL; assignmentVersion="+request.assignmentVersion());
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder reject(UUID id,WorkOrderRequests.Reject request,String key) {
        var actor=requireShop();
        return command(actor,"REJECT",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            requireStatus(row,WorkOrderStatus.PENDING_ACCEPTANCE);
            var assignment=requireShopAssignment(row,request.assignmentVersion(),actor);
            var now=Instant.now();
            if(mapper.rejectAssignment(assignment.getId(),actor.id(),request.reason().trim(),now)!=1) throw state("Assignment changed concurrently");
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_DISPATCH.name(),null,true,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            history(id,row.getStatus(),WorkOrderStatus.PENDING_DISPATCH.name(),"REJECT",request.reason().trim(),actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_REJECT,"WORK_ORDER",id,
                "Status PENDING_ACCEPTANCE -> PENDING_DISPATCH; assignmentVersion="+request.assignmentVersion()+"; reason="+request.reason().trim());
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder arrivalException(UUID id,WorkOrderRequests.ArrivalException request,String key) {
        var actor=CurrentUser.require();
        if(!isStaff(actor)&&!actor.roles().contains(Role.REPAIR_SHOP)) throw ApiException.denied();
        return command(actor,"ARRIVAL_EXCEPTION",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            if(!isStaff(actor)) requireShopAssignment(row,request.assignmentVersion(),actor);
            else requireCurrentAssignment(row,request.assignmentVersion());
            if(WorkOrderStatus.ARRIVAL_EXCEPTION.name().equals(row.getStatus())) return row;
            requireStatus(row,WorkOrderStatus.PENDING_ARRIVAL);
            var now=Instant.now();
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.ARRIVAL_EXCEPTION.name(),row.getShopId(),false,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            history(id,row.getStatus(),WorkOrderStatus.ARRIVAL_EXCEPTION.name(),"ARRIVAL_EXCEPTION",request.reason().trim(),actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_ARRIVAL_EXCEPTION,"WORK_ORDER",id,
                "Status PENDING_ARRIVAL -> ARRIVAL_EXCEPTION; reason="+request.reason().trim());
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder continueWaiting(UUID id,WorkOrderRequests.ContinueWaiting request,String key) {
        var actor=CurrentUser.require();
        if(!isStaff(actor)&&!actor.roles().contains(Role.REPAIR_SHOP)) throw ApiException.denied();
        return command(actor,"CONTINUE_WAITING",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            if(!isStaff(actor)) requireShopAssignment(row,request.assignmentVersion(),actor);
            else requireCurrentAssignment(row,request.assignmentVersion());
            requireStatus(row,WorkOrderStatus.ARRIVAL_EXCEPTION);
            var now=Instant.now();
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_ARRIVAL.name(),row.getShopId(),false,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            String reason=trim(request.reason());
            history(id,row.getStatus(),WorkOrderStatus.PENDING_ARRIVAL.name(),"CONTINUE_WAITING",reason,actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_CONTINUE_WAITING,"WORK_ORDER",id,
                "Status ARRIVAL_EXCEPTION -> PENDING_ARRIVAL"+reasonSummary(reason));
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder arrive(UUID id,WorkOrderRequests.AssignmentAction request,String key) {
        var actor=requireShop();
        return command(actor,"ARRIVE",key,id,request.assignmentVersion(),() -> {
            var row=lock(id);
            var assignment=requireShopAssignment(row,request.assignmentVersion(),actor);
            if("ACCEPTED".equals(assignment.getStatus())&&ARRIVED_OR_LATER.contains(row.getStatus())) return row;
            requireStatus(row,WorkOrderStatus.PENDING_ARRIVAL);
            var now=Instant.now();
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.ARRIVED.name(),row.getShopId(),false,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            history(id,row.getStatus(),WorkOrderStatus.ARRIVED.name(),"ARRIVE",null,actor,request.assignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_ARRIVE,"WORK_ORDER",id,
                "Status PENDING_ARRIVAL -> ARRIVED; assignmentVersion="+request.assignmentVersion());
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder cancelWorkOrder(UUID id,WorkOrderRequests.CancelWorkOrder request,String key) {
        var actor=requireStaff();
        return command(actor,"CANCEL_WORK_ORDER",key,id,null,() -> {
            var row=lock(id);
            requireVisibleRow(row,actor);
            if(WorkOrderStatus.DRAFT.name().equals(row.getStatus())) throw state("Delete a draft instead of cancelling it");
            if(Set.of(WorkOrderStatus.CANCELLED.name(),WorkOrderStatus.CLOSED.name(),WorkOrderStatus.COMPLETED.name()).contains(row.getStatus())) {
                throw state("Work order cannot be cancelled now");
            }
            if(ARRIVED_OR_LATER.contains(row.getStatus())&&!actor.roles().contains(Role.ADMIN)) throw ApiException.denied();
            var assignment=mapper.activeAssignment(id);
            var now=Instant.now();
            if(assignment!=null&&mapper.cancelAssignment(assignment.getId(),actor.id(),request.reason().trim(),now)!=1) {
                throw state("Assignment changed concurrently");
            }
            if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.CANCELLED.name(),row.getShopId(),false,actor.id(),now)!=1) {
                throw state("Work order changed concurrently");
            }
            mapper.cancellation(id,row.getOwnerUserId(),assignment==null?null:assignment.getShopId(),assignment==null?null:assignment.getAssignmentVersion(),request.notifyOwner(),request.notifyShop(),request.reason().trim(),now);
            String reason=request.reason().trim()+"; description="+request.description().trim()+"; notifyOwner="+request.notifyOwner()+"; notifyShop="+request.notifyShop();
            history(id,row.getStatus(),WorkOrderStatus.CANCELLED.name(),"CANCEL_WORK_ORDER",reason,actor,
                assignment==null?null:assignment.getAssignmentVersion(),now);
            audit.record(actor,Action.WORK_ORDER_CANCEL,"WORK_ORDER",id,
                "Status "+row.getStatus()+" -> CANCELLED; "+reason);
            return mapper.find(id);
        });
    }

    @Transactional
    public WorkOrderViews.WorkOrder bindOwner(UUID id,WorkOrderRequests.OwnerBinding request,String key) {
        var actor=requireStaff();
        validateKey(key);
        String hash=com.evinsurance.platform.document.application.FileService.hash((id+":"+json.writeValueAsString(request)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mapper.lockOwnerBindingCommand("owner-bind:"+actor.id()+":"+key);
        if(mapper.command(actor.id(),"OWNER_BIND",key)!=null&&!Objects.equals(hash,mapper.bindingCommandHash(actor.id(),key))) throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Owner binding key belongs to another request");
        // Lock before inserting the command FK: concurrent keys must not both hold
        // a parent KEY SHARE lock and then attempt to upgrade it to FOR UPDATE.
        requireVisibleRow(lock(id),actor);
        return command(actor,"OWNER_BIND",key,id,null,()->{
            mapper.bindingCommandHashSave(actor.id(),key,hash);
            var row=lock(id); requireVisibleRow(row,actor);
            if(Set.of("DRAFT","CANCELLED","COMPLETED","CLOSED").contains(row.getStatus())) throw state("This case cannot be bound now");
            if(!"PENDING".equals(row.getOwnerBindingStatus())||row.getOwnerUserId()!=null) throw ApiException.conflict("OWNER_ALREADY_BOUND","Existing owner binding cannot be transferred");
            if(!Objects.equals(row.getVersion(),request.expectedVersion())) throw state("Work order changed; refresh before binding");
            mapper.lockSubmissionGuard(); Long owner=matchOwner(row.getOwnerPhone()); var now=Instant.now();
            if(owner!=null) {
                if(mapper.bindOwner(id,row.getVersion(),owner,actor.id(),now)!=1) throw state("Owner binding changed concurrently");
                history(id,row.getStatus(),row.getStatus(),"OWNER_BIND",request.reason().trim(),actor,null,now);
            }
            audit.record(actor,Action.WORK_ORDER_OWNER_BIND,"WORK_ORDER",id,"Owner matching result="+(owner==null?"PENDING":"BOUND")+"; reason="+request.reason().trim().replaceAll("[0-9]{7,}","[REDACTED]"));
            return mapper.find(id);
        });
    }

    public PageResponse<WorkOrderRows.History> history(UUID id,int page,int size) {
        var actor=CurrentUser.require();
        requireVisible(id,actor,false);
        int offset=PageResponse.offset(page,size);
        var rows=mapper.history(id,offset,size);
        if(actor.roles().contains(Role.OWNER)) rows.forEach(r -> {r.setReason(null);r.setActorId(null);r.setActorRoles(null);});
        return new PageResponse<>(page,size,mapper.historyCount(id),rows);
    }

    private WorkOrderEntity dispatchInternal(UUID id,long shopId,CurrentUser actor,String action,String reason,
        boolean confirmDuplicate,String duplicateReason,String transferDescription) {
        var row=lock(id);
        requireStatus(row,WorkOrderStatus.PENDING_DISPATCH);
        if(caseWorkshops!=null)caseWorkshops.assertScope(shopId,id,row.getAccidentRegionId());
        if(!row.isDispatchAddressConfirmed()||(trim(row.getParkingAddress())==null&&trim(row.getAccidentAddress())==null))throw ApiException.conflict("DISPATCH_ADDRESS_REQUIRED","请先核对服务区域和实际接车地址");
        if(row.getAccidentRegionId()==null||mapper.shopEligible(shopId,row.getAccidentRegionId())!=1) {
            throw ApiException.conflict("SHOP_NOT_ELIGIBLE","Shop is disabled or outside the service region");
        }
        int days=mapper.possibleDuplicateDays();
        var duplicates=mapper.possibleShopDuplicates(id,row.getOwnerPhone(),shopId,row.getAccidentAt(),days);
        String confirmedReason=requireDuplicateDecision(duplicates,confirmDuplicate,duplicateReason);
        int version=mapper.nextAssignmentVersion(id);
        var now=Instant.now();
        mapper.insertAssignment(id,version,shopId,trim(transferDescription),actor.id(),now);
        if(mapper.transition(id,row.getVersion(),row.getStatus(),WorkOrderStatus.PENDING_ACCEPTANCE.name(),shopId,false,actor.id(),now)!=1) {
            throw state("Work order changed concurrently");
        }
        String historyReason=reason!=null?reason:confirmedReason;
        history(id,row.getStatus(),WorkOrderStatus.PENDING_ACCEPTANCE.name(),action,historyReason,actor,version,now);
        audit.record(actor,Action.WORK_ORDER_DISPATCH,"WORK_ORDER",id,
            "Status PENDING_DISPATCH -> PENDING_ACCEPTANCE; shopId="+shopId+"; assignmentVersion="+version+reasonSummary(historyReason));
        return mapper.find(id);
    }

    private WorkOrderViews.WorkOrder command(CurrentUser actor,String operation,String key,UUID id,Integer assignmentVersion,
        Supplier<WorkOrderEntity> action) {
        validateKey(key);
        var existing=mapper.command(actor.id(),operation,key);
        if(existing!=null) return stored(existing,id,assignmentVersion);
        if(mapper.reserveCommand(actor.id(),operation,key,id,assignmentVersion,Instant.now())!=1) {
            existing=mapper.command(actor.id(),operation,key);
            if(existing==null) throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Idempotency key conflict");
            return stored(existing,id,assignmentVersion);
        }
        var result=view(action.get(),true,actor);
        try {
            if(mapper.completeCommand(actor.id(),operation,key,json.writeValueAsString(result))!=1) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Idempotency result could not be stored");
            }
        } catch(JacksonException error) {
            throw new IllegalStateException("Idempotency result could not be serialized",error);
        }
        return result;
    }

    private WorkOrderViews.WorkOrder stored(WorkOrderRows.Command existing,UUID id,Integer assignmentVersion) {
        if(!existing.getWorkOrderId().equals(id)||!Objects.equals(existing.getAssignmentVersion(),assignmentVersion)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Idempotency key belongs to another target or assignment version");
        }
        if(existing.getResponseJson()==null) throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS","The original request is still in progress");
        try {
            return json.readValue(existing.getResponseJson(),WorkOrderViews.WorkOrder.class);
        } catch(JacksonException error) {
            throw new IllegalStateException("Stored idempotency result is invalid",error);
        }
    }

    private List<String> possibleDuplicates(WorkOrderEntity row) {
        return mapper.possibleDuplicates(row.getId(),row.getOwnerPhone(),row.getPolicyNo(),row.getVehicleVin(),row.getVehiclePlate(),
            row.getVehicleOtherIdentifier(),row.getAccidentAt(),mapper.possibleDuplicateDays());
    }

    private String requireDuplicateDecision(List<String> duplicates,boolean confirmed,String reason) {
        if(duplicates.isEmpty()) return null;
        if(!confirmed) throw ApiException.conflict("POSSIBLE_DUPLICATE","Possible duplicate work orders: "+String.join(",",duplicates));
        String normalized=trim(reason);
        if(normalized==null) throw ApiException.invalid("duplicateReason is required when continuing after a possible duplicate warning");
        return "Possible duplicate confirmed against "+String.join(",",duplicates)+"; reason="+normalized;
    }

    private WorkOrderEntity from(WorkOrderRequests.Draft request) {
        var row=new WorkOrderEntity();
        row.setInsuranceCompany(trim(request.insuranceCompany()));
        row.setClaimNo(trim(request.claimNo()));
        row.setClaimReportedAt(request.claimReportedAt());
        row.setOwnerName(trim(request.ownerName()));
        row.setOwnerPhone(trim(request.ownerPhone()));
        row.setPolicyNo(trim(request.policyNo()));
        row.setSurveyorName(trim(request.surveyorName()));
        row.setSurveyorPhone(trim(request.surveyorPhone()));
        row.setVehicleColor(trim(request.vehicleColor()));
        row.setVehicleBrand(trim(request.vehicleBrand()));
        row.setVehicleModel(trim(request.vehicleModel()));
        row.setVehicleVin(trim(request.vehicleVin()));
        row.setVehiclePlate(trim(request.vehiclePlate())==null?null:com.evinsurance.platform.workorder.domain.VehiclePlate.check(request.vehiclePlate()).value());
        row.setVehicleOtherIdentifier(trim(request.vehicleOtherIdentifier()));
        row.setAccidentAt(request.accidentAt());
        row.setAccidentRegionId(request.accidentRegionId());
        row.setAccidentAddress(trim(request.accidentAddress()));
        row.setAccidentDescription(trim(request.accidentDescription()));
        return row;
    }

    public List<String> formalMissing(WorkOrderEntity row) {
        var missing=new java.util.ArrayList<String>();
        String[][] values={{row.getClaimNo(),"案件号"},{row.getOwnerPhone(),"车主手机号"},{row.getSurveyorName(),"查勘员姓名"},{row.getSurveyorPhone(),"查勘员电话"}};
        for(var value:values)if(trim(value[0])==null)missing.add("请填写"+value[1]);
        if(trim(row.getVehiclePlate())==null&&trim(row.getVehicleVin())==null)missing.add("请填写车辆牌照或车架号");
        if(trim(row.getVehicleVin())==null&&trim(row.getVehiclePlate())!=null&&!com.evinsurance.platform.workorder.domain.VehiclePlate.check(row.getVehiclePlate()).acceptable())missing.add("请核对车牌号格式，或填写车架号");
        if(files.activeCount(row.getId(),"SCENE_PHOTO")==0)missing.add("请上传至少1张现场物损照片");
        return List.copyOf(missing);
    }
    private void validateFormal(WorkOrderEntity row) {
        var missing=formalMissing(row);
        if(!missing.isEmpty())throw ApiException.conflict("INTAKE_INCOMPLETE",String.join("；",missing));
    }

    @Transactional
    public WorkOrderViews.WorkOrder confirmAccidentDistrict(UUID id,WorkOrderRequests.AccidentDistrict request) {
        var actor=requireStaff();var row=lock(id);requireVisibleRow(row,actor);
        if(!Set.of("PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(row.getStatus()))throw state("当前阶段不能核对事故区县");
        if(!request.confirmed()||trim(request.reason())==null)throw ApiException.invalid("请人工核实事故区县并填写原因；不得为匹配网点修改事故事实");
        if(!Objects.equals(row.getVersion(),request.expectedVersion()))throw state("案件版本已变化，请刷新后重新核对事故区县");
        if(mapper.enabledDistrict(request.regionId())!=1)throw ApiException.invalid("请选择启用的事故区县，不得使用城市代替区县");
        if(row.getShopId()!=null&&caseWorkshops!=null)caseWorkshops.assertScope(row.getShopId(),id,request.regionId());
        if(row.getShopId()!=null&&mapper.shopEligible(row.getShopId(),request.regionId())!=1)throw ApiException.conflict("SHOP_NOT_ELIGIBLE","该变更会使当前派单网点失去服务资格，请先按既有流程处理派单");
        if(mapper.accidentDistrict(id,row.getVersion(),request.regionId(),actor.id())!=1)throw state("案件已被其他操作更新，请刷新");
        audit.record(actor,Action.WORK_ORDER_DRAFT_UPDATE,"WORK_ORDER",id,"Accident district manually reviewed; old="+row.getAccidentRegionId()+"; new="+request.regionId()+"; version="+row.getVersion()+"->"+(row.getVersion()+1)+"; reason="+request.reason().trim());
        return view(mapper.find(id),true,actor);
    }

    @Transactional public WorkOrderViews.WorkOrder supplementDispatchAccidentAddress(UUID id,long expectedVersion,String address) {
        var actor=requireStaff();var row=lock(id);requireVisibleRow(row,actor);requireStatus(row,WorkOrderStatus.PENDING_DISPATCH);
        if(row.getVersion()!=expectedVersion)throw state("案件版本已变化，请重新核对事故地址");
        String value=trim(address);if(value==null||value.length()<4||value.length()>200)throw ApiException.invalid("事故地址应为4至200字");
        if(!Objects.equals(value,row.getAccidentAddress())){
            if(mapper.supplementDispatchAccidentAddress(id,expectedVersion,value,actor.id())!=1)throw state("案件已被其他操作更新");
            audit.record(actor,Action.WORK_ORDER_DRAFT_UPDATE,"WORK_ORDER",id,"Accident address supplemented at final dispatch; previousAddressPresent="+(trim(row.getAccidentAddress())!=null)+"; newLength="+value.length()+"; address omitted");
        }
        return view(mapper.find(id),true,actor);
    }

    public PageResponse<WorkOrderRows.CityOption> accidentDistricts(String query,int page,int size) {
        requireStaff();int offset=PageResponse.offset(page,size);String search=query==null?"":query.trim();
        if(search.length()>100)throw ApiException.invalid("区县搜索内容不能超过100字符");
        return new PageResponse<>(page,size,mapper.districtCount(search),mapper.districts(search,offset,size));
    }

    @Transactional
    public WorkOrderViews.WorkOrder confirmDispatchLocation(UUID id,WorkOrderRequests.DispatchLocation request) {
        var actor=requireStaff();var row=lock(id);requireVisibleRow(row,actor);
        if(!Set.of("PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION","ARRIVED").contains(row.getStatus()))throw state("当前阶段不能修改接车地址");
        if("ARRIVED".equals(row.getStatus())&&!actor.roles().contains(Role.ADMIN))throw ApiException.denied();
        if(!request.confirmed())throw ApiException.invalid("请核对并确认实际接车地址");
        if(!Objects.equals(row.getVersion(),request.expectedVersion()))throw state("Work order changed; refresh before confirming address");
        if(mapper.regionEnabled(request.regionId())!=1)throw ApiException.invalid("请选择有效服务区域");
        if(mapper.dispatchLocation(id,row.getVersion(),request.regionId(),request.address().trim(),actor.id())!=1)throw state("Work order changed concurrently");
        audit.record(actor,Action.WORK_ORDER_DRAFT_UPDATE,"WORK_ORDER",id,"Dispatch location reviewed; address excluded; region="+request.regionId());
        return view(mapper.find(id),true,actor);
    }

    private void requireVehicleIdentifier(String vin,String plate,String other) {
        if(trim(vin)==null&&trim(plate)==null&&trim(other)==null) {
            throw ApiException.invalid("At least one vehicle identifier is required: vehicleVin, vehiclePlate or vehicleOtherIdentifier");
        }
    }

    private String required(String value,String field) {
        String normalized=trim(value);
        if(normalized==null) throw ApiException.invalid(field+" is required");
        return normalized;
    }

    private Long matchOwner(String phone) {
        var ids=mapper.ownersByPhone(phone);
        return ids.size()==1?ids.get(0):null;
    }

    private CurrentUser requireStaff() {
        var actor=CurrentUser.require();
        if(!isStaff(actor)) throw ApiException.denied();
        return actor;
    }

    private CurrentUser requireShop() {
        var actor=CurrentUser.require();
        if(!actor.roles().contains(Role.REPAIR_SHOP)||actor.shopId()==null) throw ApiException.denied();
        return actor;
    }

    private boolean isStaff(CurrentUser actor) {
        return actor.roles().stream().map(Enum::name).anyMatch(STAFF::contains);
    }

    private String scope(CurrentUser actor) {
        if(actor.roles().contains(Role.ADMIN)) return "ADMIN";
        if(actor.roles().contains(Role.CUSTOMER_SERVICE)) return "CUSTOMER_SERVICE";
        if(actor.roles().contains(Role.REPAIR_SHOP)&&actor.shopId()!=null) return "SHOP";
        if(actor.roles().contains(Role.OWNER)) return "OWNER";
        throw ApiException.denied();
    }

    private void requireDraftOwner(WorkOrderEntity row,CurrentUser actor) {
        requireStatus(row,WorkOrderStatus.DRAFT);
        if(!actor.roles().contains(Role.ADMIN)&&row.getCreatedBy()!=actor.id()) throw ApiException.denied();
    }

    private WorkOrderEntity requireVisible(UUID id,CurrentUser actor,boolean lock) {
        var row=lock?mapper.lock(id):mapper.find(id);
        if(row==null) throw ApiException.missing("Work order");
        requireVisibleRow(row,actor);
        return row;
    }

    private void requireVisibleRow(WorkOrderEntity row,CurrentUser actor) {
        if(actor.roles().contains(Role.ADMIN)) return;
        if(actor.roles().contains(Role.CUSTOMER_SERVICE)) {
            if(!WorkOrderStatus.DRAFT.name().equals(row.getStatus())||row.getCreatedBy()==actor.id()) return;
            throw ApiException.denied();
        }
        if(actor.roles().contains(Role.REPAIR_SHOP)&&actor.shopId()!=null&&actor.shopId().equals(row.getShopId())) return;
        if(actor.roles().contains(Role.OWNER)&&row.getOwnerUserId()!=null&&actor.id()==row.getOwnerUserId()) return;
        throw ApiException.denied();
    }

    private WorkOrderEntity lock(UUID id) {
        var row=mapper.lock(id);
        if(row==null) throw ApiException.missing("Work order");
        return row;
    }

    private WorkOrderRows.Assignment requireCurrentAssignment(WorkOrderEntity row,int version) {
        var assignment=mapper.assignment(row.getId(),version);
        var active=mapper.activeAssignment(row.getId());
        if(assignment==null||active==null||!assignment.getId().equals(active.getId())) throw state("Assignment version is no longer current");
        return assignment;
    }

    private WorkOrderRows.Assignment requireShopAssignment(WorkOrderEntity row,int version,CurrentUser actor) {
        var assignment=requireCurrentAssignment(row,version);
        if(actor.shopId()==null||!actor.shopId().equals(assignment.getShopId())||!actor.shopId().equals(row.getShopId())) throw ApiException.denied();
        return assignment;
    }

    private void requireStatus(WorkOrderEntity row,WorkOrderStatus status) {
        if(!status.name().equals(row.getStatus())) throw state("Expected status "+status.name());
    }

    private ApiException state(String message) {
        return ApiException.conflict("STATE_CONFLICT",message);
    }

    private WorkOrderStatus parseStatus(String status) {
        try { return WorkOrderStatus.valueOf(status); }
        catch(Exception error) { throw ApiException.invalid("Unknown work order status"); }
    }

    private void history(UUID id,String from,String to,String action,String reason,CurrentUser actor,Integer assignmentVersion,Instant now) {
        String roles=actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
        String trace=TraceContext.currentTraceId();
        mapper.insertHistory(id,from,to,action,reason,actor.id(),roles,assignmentVersion,
            trace==null?UUID.randomUUID().toString():trace,now);
    }

    private WorkOrderViews.WorkOrder view(WorkOrderEntity row,boolean detail,CurrentUser actor) {
        return view(row,detail,actor,mapper.phases(java.util.List.of(row.getId())).getFirst());
    }
    private WorkOrderViews.WorkOrder view(WorkOrderEntity row,boolean detail,CurrentUser actor,WorkOrderRows.Phase phase) {
        String ownerName=row.getOwnerName();
        String ownerPhone=row.getOwnerPhone();
        String policyNo=row.getPolicyNo();
        String vin=row.getVehicleVin();
        String plate=row.getVehiclePlate();
        String other=row.getVehicleOtherIdentifier();
        String address=row.getAccidentAddress();
        String description=row.getAccidentDescription();
        if(actor.roles().contains(Role.REPAIR_SHOP)) {
            policyNo=maskIdentifier(policyNo);
            if(!detail) {
                ownerName=maskName(ownerName);
                ownerPhone=maskPhone(ownerPhone);
                vin=maskIdentifier(vin);
                plate=maskIdentifier(plate);
                other=maskIdentifier(other);
                address=null;
                description=null;
            }
        }
        WorkOrderRows.Assignment assignment=null;
        if(detail&&!actor.roles().contains(Role.OWNER)) assignment=mapper.activeAssignment(row.getId());
        return new WorkOrderViews.WorkOrder(row.getId(),row.getBusinessNo(),row.getInsuranceCompany(),row.getClaimNo(),
            row.getClaimReportedAt(),row.getDataSource(),row.getCurrentResponsibleId(),row.getOwnerBindingStatus(),ownerName,ownerPhone,
            policyNo,row.getVehicleBrand(),row.getVehicleModel(),vin,plate,other,row.getAccidentAt(),row.getAccidentRegionId(),address,
            description,row.getShopId(),row.getStatus(),row.getStatusStartedAt(),row.getVersion(),row.getCreatedBy(),row.getCreatedAt(),
            row.getUpdatedAt(),assignment,node(row,phase),nodeActor(row,phase),row.getSurveyorName(),
            actor.roles().contains(Role.ADMIN)||actor.roles().contains(Role.CUSTOMER_SERVICE)?row.getSurveyorPhone():null,row.getVehicleColor(),row.isDispatchAddressConfirmed(),
            actor.roles().contains(Role.ADMIN)||actor.roles().contains(Role.CUSTOMER_SERVICE)?row.getParkingRegionId():null,
            actor.roles().contains(Role.ADMIN)||actor.roles().contains(Role.CUSTOMER_SERVICE)?row.getParkingAddress():null);
    }

    private String node(WorkOrderEntity row,WorkOrderRows.Phase phase){if(java.util.Set.of("WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION").contains(row.getStatus())&&(phase==null||!phase.formalCurrent()))return phase!=null&&phase.rawCurrent()?"QUOTE_REVIEW":"SHOP_QUOTE";var n=com.evinsurance.platform.workorder.domain.WorkflowNode.current(row.getStatus(),phase!=null&&phase.amountPresent());return n==null?"UNKNOWN":n.name();}
    private String nodeActor(WorkOrderEntity row,WorkOrderRows.Phase phase){if(java.util.Set.of("WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION").contains(row.getStatus())&&(phase==null||!phase.formalCurrent()))return phase!=null&&phase.rawCurrent()?"客服":"网点";return switch(row.getStatus()){
        case "DRAFT","PENDING_DISPATCH","ARRIVAL_EXCEPTION" -> "管理员或客服";
        case "PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVED","WAITING_QUOTE" -> "网点";
        case "QUOTE_REVIEWING","WAITING_INSURER_ASSESSMENT","WAITING_INSURER_PAYMENT","WAITING_SHOP_SETTLEMENT" -> "客服";
        case "WAITING_REPAIR_AUTHORIZATION" -> phase!=null&&phase.confirmed()&&phase.amountPresent()?"网点":"客服";
        case "REPAIRING" -> "网点或客服";
        case "WAITING_OWNER_CONFIRMATION" -> "车主或客服";
        default -> "无需办理";
    };}
    private void validateKey(String key) {
        if(key==null||key.isBlank()||key.length()>128) throw ApiException.invalid("Idempotency-Key is required and must be at most 128 characters");
    }

    private String trim(String value) {
        if(value==null) return null;
        String normalized=value.trim();
        return normalized.isEmpty()?null:normalized;
    }

    private String maskName(String value) {
        if(value==null||value.isEmpty()) return value;
        return value.substring(0,1)+"**";
    }

    private String maskPhone(String value) {
        if(value==null||value.length()<7) return value==null?null:"****";
        return value.substring(0,3)+"****"+value.substring(value.length()-4);
    }

    private String maskIdentifier(String value) {
        if(value==null) return null;
        if(value.length()<=8) return "****";
        return value.substring(0,4)+"****"+value.substring(value.length()-4);
    }

    private String reasonSummary(String reason) {
        return reason==null?"":"; "+reason;
    }
}
