package com.evinsurance.platform.pricing.infrastructure;
import java.util.UUID;import java.time.Instant;import com.baomidou.mybatisplus.annotation.*;
@TableName("price_import_error")
public class ImportErrorEntity {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;} public void setId(Long v){id=v;}
 private Long batchId;
 public Long getBatchId(){return batchId;} public void setBatchId(Long v){batchId=v;}
 private Integer rowNumber;
 public Integer getRowNumber(){return rowNumber;} public void setRowNumber(Integer v){rowNumber=v;}
 private String field;
 public String getField(){return field;} public void setField(String v){field=v;}
 private String reason;
 public String getReason(){return reason;} public void setReason(String v){reason=v;}
 private String originalValue;
 public String getOriginalValue(){return originalValue;} public void setOriginalValue(String v){originalValue=v;}
}
