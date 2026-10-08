package com.evinsurance.platform.workorder.infrastructure;

import java.time.Instant;
import java.util.UUID;

public class WorkOrderEntity {
    private UUID id;
    private String businessNo;
    private String insuranceCompany;
    private String claimNo;
    private Instant claimReportedAt;
    private String dataSource;
    private Long currentResponsibleId;
    private Long ownerUserId;
    private String ownerBindingStatus;
    private String ownerName;
    private String ownerPhone;
    private String policyNo;
    private String vehicleBrand;
    private String vehicleModel;
    private String vehicleVin;
    private String vehiclePlate;
    private String vehicleOtherIdentifier;
    private Instant accidentAt;
    private Long accidentRegionId;
    private String accidentAddress;
    private String accidentDescription;
    private Long shopId;
    private String status;
    private Instant statusStartedAt;
    private Long version;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public String getBusinessNo(){return businessNo;} public void setBusinessNo(String v){businessNo=v;}
    public String getInsuranceCompany(){return insuranceCompany;} public void setInsuranceCompany(String v){insuranceCompany=v;}
    public String getClaimNo(){return claimNo;} public void setClaimNo(String v){claimNo=v;}
    public Instant getClaimReportedAt(){return claimReportedAt;} public void setClaimReportedAt(Instant v){claimReportedAt=v;}
    public String getDataSource(){return dataSource;} public void setDataSource(String v){dataSource=v;}
    public Long getCurrentResponsibleId(){return currentResponsibleId;} public void setCurrentResponsibleId(Long v){currentResponsibleId=v;}
    public Long getOwnerUserId(){return ownerUserId;} public void setOwnerUserId(Long v){ownerUserId=v;}
    public String getOwnerBindingStatus(){return ownerBindingStatus;} public void setOwnerBindingStatus(String v){ownerBindingStatus=v;}
    public String getOwnerName(){return ownerName;} public void setOwnerName(String v){ownerName=v;}
    public String getOwnerPhone(){return ownerPhone;} public void setOwnerPhone(String v){ownerPhone=v;}
    public String getPolicyNo(){return policyNo;} public void setPolicyNo(String v){policyNo=v;}
    public String getVehicleBrand(){return vehicleBrand;} public void setVehicleBrand(String v){vehicleBrand=v;}
    public String getVehicleModel(){return vehicleModel;} public void setVehicleModel(String v){vehicleModel=v;}
    public String getVehicleVin(){return vehicleVin;} public void setVehicleVin(String v){vehicleVin=v;}
    public String getVehiclePlate(){return vehiclePlate;} public void setVehiclePlate(String v){vehiclePlate=v;}
    public String getVehicleOtherIdentifier(){return vehicleOtherIdentifier;} public void setVehicleOtherIdentifier(String v){vehicleOtherIdentifier=v;}
    public Instant getAccidentAt(){return accidentAt;} public void setAccidentAt(Instant v){accidentAt=v;}
    public Long getAccidentRegionId(){return accidentRegionId;} public void setAccidentRegionId(Long v){accidentRegionId=v;}
    public String getAccidentAddress(){return accidentAddress;} public void setAccidentAddress(String v){accidentAddress=v;}
    public String getAccidentDescription(){return accidentDescription;} public void setAccidentDescription(String v){accidentDescription=v;}
    public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Instant getStatusStartedAt(){return statusStartedAt;} public void setStatusStartedAt(Instant v){statusStartedAt=v;}
    public Long getVersion(){return version;} public void setVersion(Long v){version=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public Long getUpdatedBy(){return updatedBy;} public void setUpdatedBy(Long v){updatedBy=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
