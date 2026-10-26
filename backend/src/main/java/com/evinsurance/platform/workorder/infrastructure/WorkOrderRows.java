package com.evinsurance.platform.workorder.infrastructure;

import java.time.Instant;
import java.util.UUID;

public final class WorkOrderRows {
    public record CityOption(long id,String code,String name) {}
    public record Phase(UUID caseId,boolean amountPresent,boolean lossPresent,boolean confirmed,boolean rawCurrent,boolean formalCurrent) {}
    public record Milestone(String status,Instant occurredAt) {}
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
        private Long destinationShopId;
        private String destinationName;
        private String destinationAddress;
        private String locationPrecision;
        private String shopName;
        private String shopAddress;

        public Long getDestinationShopId(){return destinationShopId;} public void setDestinationShopId(Long v){destinationShopId=v;}
        public String getDestinationName(){return destinationName;} public void setDestinationName(String v){destinationName=v;}
        public String getDestinationAddress(){return destinationAddress;} public void setDestinationAddress(String v){destinationAddress=v;}
        public String getLocationPrecision(){return locationPrecision;} public void setLocationPrecision(String v){locationPrecision=v;}
        public String getShopName(){return shopName;} public void setShopName(String v){shopName=v;}
        public String getShopAddress(){return shopAddress;} public void setShopAddress(String v){shopAddress=v;}

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
        private Double distanceMetres;
        public Double getDistanceMetres(){return distanceMetres;} public void setDistanceMetres(Double v){distanceMetres=v;}
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
