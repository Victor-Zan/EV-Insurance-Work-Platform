package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
@TableName("price_version")
public class PriceVersion {
 @TableId(type=IdType.AUTO)
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
}
