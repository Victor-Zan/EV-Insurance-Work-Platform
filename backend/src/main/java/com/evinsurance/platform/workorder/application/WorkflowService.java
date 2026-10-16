package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.document.domain.FileCategory;
import com.evinsurance.platform.document.domain.FileStagePolicy;
import com.evinsurance.platform.document.infrastructure.FileMapper;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.identity.domain.CurrentUser;
import com.evinsurance.platform.identity.domain.Role;
import com.evinsurance.platform.quotation.application.QuotationLifecycle;
import com.evinsurance.platform.quotation.infrastructure.QuotationMapper;
import com.evinsurance.platform.workorder.domain.WorkflowNode;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
    public record Node(String key,String label,String state,Instant completedAt) {}
    public record Material(String category,boolean required,boolean present,boolean satisfied,boolean exceptionAccepted,boolean canUpload,boolean shopUploaded) {}
    public record View(UUID caseId,String status,long workOrderVersion,int quotationVersion,int basisVersion,
                       String currentNode,String actorRole,String action,boolean canAct,boolean canContinue,
                       List<String> missing,List<Node> nodes,List<Material> materials,boolean legacyNeedsReview) {}

    private final WorkOrderService workOrders;
    private final WorkOrderMapper orders;
    private final QuotationMapper quotes;
    private final QuotationLifecycle lifecycle;
    private final FileMapper files;
    private final com.evinsurance.platform.funds.application.FundsService funds;
    public WorkflowService(WorkOrderService workOrders,WorkOrderMapper orders,QuotationMapper quotes,QuotationLifecycle lifecycle,FileMapper files,com.evinsurance.platform.funds.application.FundsService funds) {
        this.workOrders=workOrders;this.orders=orders;this.quotes=quotes;this.lifecycle=lifecycle;this.files=files;this.funds=funds;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(UUID id) {
        var actor=CurrentUser.require();
        if(!actor.roles().contains(Role.ADMIN)&&!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
        var order=workOrders.get(id); // Reuse draft creator and staff data scope checks.
        var row=orders.find(id);
        var c=quotes.context(id);
        var assignment=order.currentAssignment();
        var counts=new EnumMap<FileCategory,FileMapper.Summary>(FileCategory.class);
        for(var summary:files.summary(id,order.shopId(),assignment==null?null:assignment.getAssignmentVersion()))counts.put(FileCategory.valueOf(summary.category()),summary);
        var assessment=c==null||c.assessmentId==null?null:quotes.assessment(c.assessmentId);
        boolean currentFormal=lifecycle.hasCurrentFormal(row);
        boolean currentAmount=assessment!=null&&(Objects.equals(assessment.sourceFormalId(),c.formalQuoteId)||c.formalQuoteId==null);
        boolean loss=present(counts,FileCategory.LOSS_ASSESSMENT);
        boolean terminal=Set.of("CANCELLED","CLOSED").contains(order.status());
        String displayStatus=order.status();
        if(!terminal&&Set.of("WAITING_INSURER_ASSESSMENT","WAITING_REPAIR_AUTHORIZATION").contains(displayStatus)&&!currentFormal){
            var oldRaw=c==null||c.rawQuoteId==null?null:quotes.raw(c.rawQuoteId);
            boolean currentRaw=oldRaw!=null&&oldRaw.workOrderId().equals(id)&&assignment!=null&&"ACCEPTED".equals(assignment.getStatus())&&Objects.equals(order.shopId(),oldRaw.shopId())&&oldRaw.assignmentVersion()==assignment.getAssignmentVersion();
            displayStatus=currentRaw?"QUOTE_REVIEWING":"WAITING_QUOTE";
        }
        if(terminal) { var last=orders.latestHistory(id); displayStatus=last!=null&&order.status().equals(last.getToStatus())?last.getFromStatus():"UNKNOWN"; }
        var current=WorkflowNode.current(displayStatus,currentAmount,loss);
        String role="NONE",action="NONE";
        var missing=new ArrayList<String>();
        if(current!=null&&!terminal&&current!=WorkflowNode.COMPLETE) {
            switch(current) {
                case CREATE -> {role="ADMIN_OR_CUSTOMER_SERVICE";action="SUBMIT_WORK_ORDER";missing.addAll(workOrders.formalMissing(row));}
                case DISPATCH_ARRIVAL -> {
                    if("PENDING_DISPATCH".equals(displayStatus)){role="ADMIN_OR_CUSTOMER_SERVICE";action="DISPATCH";if(!row.isDispatchAddressConfirmed())missing.add("请核对服务区域和实际接车地址");}
                    else if("ARRIVAL_EXCEPTION".equals(displayStatus)){role="ADMIN_OR_CUSTOMER_SERVICE";action="HANDLE_ARRIVAL_EXCEPTION";}
                    else {role="REPAIR_SHOP";action="PENDING_ACCEPTANCE".equals(displayStatus)?"ACCEPT_ASSIGNMENT":"CONFIRM_ARRIVAL";}
                }
                case SHOP_QUOTE -> {role="REPAIR_SHOP";action="SUBMIT_RAW_QUOTE";}
                case QUOTE_REVIEW -> {
                    role="CUSTOMER_SERVICE";action="ISSUE_FORMAL_QUOTE";
                    if(c==null||c.rawQuoteId==null)missing.add("缺少当前网点原始报价");
                    if(!present(counts,FileCategory.SCENE_PHOTO))missing.add("请上传现场物损照片");
                }
                case INSURER_ASSESSMENT -> {
                    role="CUSTOMER_SERVICE";action="RECORD_ASSESSMENT";
                    if(!currentFormal)missing.add("请先完成当前报价审核");
                    if(!currentAmount)missing.add("最终核损金额未录入");
                    if(!loss)missing.add("缺少有效核损单");
                }
                case REPAIR -> {
                    if("REPAIRING".equals(displayStatus)){
                        role="REPAIR_SHOP_OR_CUSTOMER_SERVICE";action="SUBMIT_COMPLETION";
                        if(!eligible(counts,FileCategory.COMPLETION_PHOTO))missing.add("需网点上传本次派单有效JPG/PNG完工照");
                        break;
                    }
                    role="CUSTOMER_SERVICE";
                    if(c!=null&&c.insurerConfirmed&&c.serviceConfirmed){role="REPAIR_SHOP";action="START_REPAIR";}
                    else action=c!=null&&c.insurerConfirmed?"CONFIRM_CUSTOMER_SERVICE":"CONFIRM_INSURER";
                    // Keep already authorized legacy evidence valid; new confirmations require current formal context.
                    if(!"START_REPAIR".equals(action)&&!currentFormal)missing.add("请先完成当前报价审核");
                    if(!lifecycle.hasCurrentAssessment(c))missing.add("请核对当前最终核损单及采用金额");
                }
                case RECEIPT -> {role="OWNER_OR_CUSTOMER_SERVICE";action="CONFIRM_RECEIPT";}
                case FUNDS -> {
                    role="CUSTOMER_SERVICE";boolean receive="WAITING_INSURER_PAYMENT".equals(displayStatus);action=receive?"CONFIRM_FUNDS_RECEIVE":"CONFIRM_FUNDS_PAY";
                    if(!funds.get(id).path(receive?"canConfirmReceive":"canConfirmPay").asBoolean())missing.add(receive?"请补齐应收金额、到账流水和凭证编号后确认":"请补齐应付金额、结算流水和凭证编号后确认");
                }
                default -> { }
            }
        }
        boolean canAct=!terminal&&switch(role) {
            case "ADMIN_OR_CUSTOMER_SERVICE" -> true;
            case "CUSTOMER_SERVICE","REPAIR_SHOP_OR_CUSTOMER_SERVICE","OWNER_OR_CUSTOMER_SERVICE" -> actor.roles().contains(Role.CUSTOMER_SERVICE);
            default -> false;
        };
        var times=new HashMap<String,Instant>();
        for(var milestone:orders.milestones(id))times.put(milestone.status(),milestone.occurredAt());
        var raw=c==null||c.rawQuoteId==null?null:quotes.raw(c.rawQuoteId);
        var formal=c==null||c.formalQuoteId==null?null:quotes.formal(c.formalQuoteId);
        boolean stagedFunds=current!=null&&current.ordinal()>=WorkflowNode.FUNDS.ordinal()&&funds.get(id).path("stagedFlow").asBoolean();
        var nodes=new ArrayList<Node>();
        for(var node:WorkflowNode.values()) {
            String state="UNRECORDED";
            if(current!=null)state=node.ordinal()<current.ordinal()?"DONE":node==current?(terminal?"STOPPED":node==WorkflowNode.COMPLETE?"DONE":canAct?"CURRENT":"WAITING"):(terminal?"TERMINATED":"LOCKED");
            if("DONE".equals(state)&&((node==WorkflowNode.SHOP_QUOTE&&raw==null)||(node==WorkflowNode.QUOTE_REVIEW&&formal==null)||(node==WorkflowNode.INSURER_ASSESSMENT&&(assessment==null||!loss))))state="UNRECORDED";
            if(node==WorkflowNode.FUNDS&&"DONE".equals(state)&&!stagedFunds)state="UNRECORDED";
            Instant time=null;
            if("DONE".equals(state))time=switch(node) {
                case CREATE -> times.get("PENDING_DISPATCH");
                case DISPATCH_ARRIVAL -> times.get("ARRIVED");
                case SHOP_QUOTE -> raw==null?null:raw.createdAt();
                case QUOTE_REVIEW -> formal==null?null:formal.createdAt();
                case INSURER_ASSESSMENT -> assessment==null?null:later(assessment.createdAt(),counts.containsKey(FileCategory.LOSS_ASSESSMENT)?counts.get(FileCategory.LOSS_ASSESSMENT).lastUploadedAt():null);
                case REPAIR -> times.get("WAITING_OWNER_CONFIRMATION");
                case RECEIPT -> times.getOrDefault("WAITING_INSURER_PAYMENT",times.get("COMPLETED"));
                case FUNDS,COMPLETE -> times.get("COMPLETED");
            };
            nodes.add(new Node(node.name(),node.label,state,time));
        }
        var materials=new ArrayList<Material>();
        for(var category:FileCategory.values()) {
            boolean allowed=category!=FileCategory.NOTICE&&FileStagePolicy.allowed(order.status(),category,currentFormal);
            boolean present=present(counts,category);
            if(!allowed&&!present)continue;
            boolean required=(category==FileCategory.SCENE_PHOTO&&(current==WorkflowNode.CREATE||current==WorkflowNode.QUOTE_REVIEW))
                ||(category==FileCategory.LOSS_ASSESSMENT&&(current==WorkflowNode.INSURER_ASSESSMENT||(current==WorkflowNode.REPAIR&&"WAITING_REPAIR_AUTHORIZATION".equals(displayStatus))))
                ||(category==FileCategory.COMPLETION_PHOTO&&current==WorkflowNode.REPAIR&&"REPAIRING".equals(displayStatus));
            boolean exception=category==FileCategory.NOTICE&&!present&&!blank(files.missingReason(id));
            materials.add(new Material(category.name(),required,present,eligible(counts,category)||exception,exception,allowed&&(actor.roles().contains(Role.CUSTOMER_SERVICE)||(category==FileCategory.SCENE_PHOTO&&actor.roles().contains(Role.ADMIN))),counts.containsKey(category)&&counts.get(category).shopUploadedCount()>0));
        }
        boolean legacy=current==null||(assessment!=null&&!currentFormal&&!terminal)
            ||(current.ordinal()>=WorkflowNode.REPAIR.ordinal()&&(!currentFormal||!currentAmount||!loss));
        return new View(id,order.status(),order.version(),c==null?0:c.version,c==null?0:c.basisVersion,current==null?"UNKNOWN":current.name(),role,action,canAct,canAct&&missing.isEmpty(),List.copyOf(missing),List.copyOf(nodes),List.copyOf(materials),legacy);
    }
    private static boolean present(Map<FileCategory,FileMapper.Summary> counts,FileCategory category){return counts.containsKey(category)&&counts.get(category).count()>0;}
    private static boolean eligible(Map<FileCategory,FileMapper.Summary> counts,FileCategory category){return counts.containsKey(category)&&counts.get(category).eligibleCount()>0;}
    private static boolean blank(String text){return text==null||text.isBlank();}
    private static Instant later(Instant left,Instant right){return right!=null&&right.isAfter(left)?right:left;}
}
