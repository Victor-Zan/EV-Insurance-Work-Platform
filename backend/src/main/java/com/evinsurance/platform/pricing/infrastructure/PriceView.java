package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;

public class PriceView {
 private Long id;
 public Long getId(){return id;} public void setId(Long v){id=v;}
 private Long recordId;
 public Long getRecordId(){return recordId;} public void setRecordId(Long v){recordId=v;}
 private Integer versionNo;
 public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
 @JsonFormat(shape=JsonFormat.Shape.STRING)
 private BigDecimal amount;
 public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
 private String currency;
 public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;}
 private Long sourceId;
 public Long getSourceId(){return sourceId;} public void setSourceId(Long v){sourceId=v;}
 private String sourceCode;
 public String getSourceCode(){return sourceCode;} public void setSourceCode(String v){sourceCode=v;}
 private String sourceName;
 public String getSourceName(){return sourceName;} public void setSourceName(String v){sourceName=v;}
 private String sourceKind;
 public String getSourceKind(){return sourceKind;} public void setSourceKind(String v){sourceKind=v;}
 private LocalDate effectiveFrom;
 public LocalDate getEffectiveFrom(){return effectiveFrom;} public void setEffectiveFrom(LocalDate v){effectiveFrom=v;}
 private LocalDate effectiveTo;
 public LocalDate getEffectiveTo(){return effectiveTo;} public void setEffectiveTo(LocalDate v){effectiveTo=v;}
 private Long previousVersionId;
 public Long getPreviousVersionId(){return previousVersionId;} public void setPreviousVersionId(Long v){previousVersionId=v;}
 private Long closedByVersionId;
 public Long getClosedByVersionId(){return closedByVersionId;} public void setClosedByVersionId(Long v){closedByVersionId=v;}
 private Long batchId;
 public Long getBatchId(){return batchId;} public void setBatchId(Long v){batchId=v;}
 private Instant createdAt;
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 private Long createdBy;
 public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
 private Long partId;
 public Long getPartId(){return partId;} public void setPartId(Long v){partId=v;}
 private String internalCode;
 public String getInternalCode(){return internalCode;} public void setInternalCode(String v){internalCode=v;}
 private String partName;
 public String getPartName(){return partName;} public void setPartName(String v){partName=v;}
 private Long modelId;
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 private String modelName;
 public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;}
 private Long brandId;
 public Long getBrandId(){return brandId;} public void setBrandId(Long v){brandId=v;}
 private String brandName;
 public String getBrandName(){return brandName;} public void setBrandName(String v){brandName=v;}
 private String priceType;
 public String getPriceType(){return priceType;} public void setPriceType(String v){priceType=v;}
 private String scope;
 public String getScope(){return scope;} public void setScope(String v){scope=v;}
 private Long regionId;
 public Long getRegionId(){return regionId;} public void setRegionId(Long v){regionId=v;}
 private String regionName;
 public String getRegionName(){return regionName;} public void setRegionName(String v){regionName=v;}
 private Long shopId;
 public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
 private String shopName;
 public String getShopName(){return shopName;} public void setShopName(String v){shopName=v;}
}
