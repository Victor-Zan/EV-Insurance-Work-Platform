package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;

public class ResolvedImportRow {
 private Integer rowNumber;
 public Integer getRowNumber(){return rowNumber;} public void setRowNumber(Integer v){rowNumber=v;}
 private Long partId;
 public Long getPartId(){return partId;} public void setPartId(Long v){partId=v;}
 private Long modelId;
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 private Long brandId;
 public Long getBrandId(){return brandId;} public void setBrandId(Long v){brandId=v;}
 private String partName;
 public String getPartName(){return partName;} public void setPartName(String v){partName=v;}
 private Long regionId;
 public Long getRegionId(){return regionId;} public void setRegionId(Long v){regionId=v;}
 private Long shopId;
 public Long getShopId(){return shopId;} public void setShopId(Long v){shopId=v;}
 private Long sourceId;
 public Long getSourceId(){return sourceId;} public void setSourceId(Long v){sourceId=v;}
 private Boolean sourceEnabled;
 public Boolean getSourceEnabled(){return sourceEnabled;} public void setSourceEnabled(Boolean v){sourceEnabled=v;}
 private Boolean aliasValid;
 public Boolean getAliasValid(){return aliasValid;} public void setAliasValid(Boolean v){aliasValid=v;}
 private Boolean partModelValid;
 public Boolean getPartModelValid(){return partModelValid;} public void setPartModelValid(Boolean v){partModelValid=v;}
}
