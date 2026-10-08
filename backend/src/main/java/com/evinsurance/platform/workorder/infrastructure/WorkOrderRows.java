package com.evinsurance.platform.workorder.infrastructure;

import java.time.Instant;
import java.util.UUID;

public final class WorkOrderRows {
    private WorkOrderRows() {}

    public static class Assignment {
        private Long id;
        private UUID workOrderId;
        private Integer assignmentVersion;
        private Long shopId;
        private String status;
        private String reason;
        private String transferDescription;
        private Long assignedBy;
        private Instant assignedAt;

        public Long getId(){return id;} public void setId(Long v){id=v;}
        public UUID getWorkOrderId(){return workOrderId;} public void setWorkOrderId(UUID v){workOrderId=v;}
        public Integer getAssignmentVersion(){return assignmentVersion;} public void setAssignmentVersion(Integer v){assignmentVersion=v;}
        public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
        public String getStatus(){return status;} public void setStatus(String v){status=v;}
        public String getReason(){return reason;} public void setReason(String v){reason=v;}
        public String getTransferDescription(){return transferDescription;} public void setTransferDescription(String v){transferDescription=v;}
        public Long getAssignedBy(){return assignedBy;} public void setAssignedBy(Long v){assignedBy=v;}
        public Instant getAssignedAt(){return assignedAt;} public void setAssignedAt(Instant v){assignedAt=v;}
    }

    public static class History {
        private Long id;
        private String fromStatus;
        private String toStatus;
        private String action;
        private String reason;
        private Long actorId;
        private String actorRoles;
        private Integer assignmentVersion;
        private Instant occurredAt;

        public Long getId(){return id;} public void setId(Long v){id=v;}
        public String getFromStatus(){return fromStatus;} public void setFromStatus(String v){fromStatus=v;}
        public String getToStatus(){return toStatus;} public void setToStatus(String v){toStatus=v;}
        public String getAction(){return action;} public void setAction(String v){action=v;}
        public String getReason(){return reason;} public void setReason(String v){reason=v;}
        public Long getActorId(){return actorId;} public void setActorId(Long v){actorId=v;}
        public String getActorRoles(){return actorRoles;} public void setActorRoles(String v){actorRoles=v;}
        public Integer getAssignmentVersion(){return assignmentVersion;} public void setAssignmentVersion(Integer v){assignmentVersion=v;}
        public Instant getOccurredAt(){return occurredAt;} public void setOccurredAt(Instant v){occurredAt=v;}
    }

    public static class ShopOption {
        private Long id;
        private String code;
        private String name;
        private String address;
        public Long getId(){return id;} public void setId(Long v){id=v;}
        public String getCode(){return code;} public void setCode(String v){code=v;}
        public String getName(){return name;} public void setName(String v){name=v;}
        public String getAddress(){return address;} public void setAddress(String v){address=v;}
    }

    public static class Command {
        private UUID workOrderId;
        private Integer assignmentVersion;
        private String responseJson;
        public UUID getWorkOrderId(){return workOrderId;} public void setWorkOrderId(UUID v){workOrderId=v;}
        public Integer getAssignmentVersion(){return assignmentVersion;} public void setAssignmentVersion(Integer v){assignmentVersion=v;}
        public String getResponseJson(){return responseJson;} public void setResponseJson(String v){responseJson=v;}
    }
}
