package com.evinsurance.platform.pricing.infrastructure;
import java.util.UUID;import java.time.Instant;import com.baomidou.mybatisplus.annotation.*;

public class StoredImportRow {
 private Integer rowNumber;
 public Integer getRowNumber(){return rowNumber;} public void setRowNumber(Integer v){rowNumber=v;}
 private String rawValues;
 public String getRawValues(){return rawValues;} public void setRawValues(String v){rawValues=v;}
 private String result;
 public String getResult(){return result;} public void setResult(String v){result=v;}
}
