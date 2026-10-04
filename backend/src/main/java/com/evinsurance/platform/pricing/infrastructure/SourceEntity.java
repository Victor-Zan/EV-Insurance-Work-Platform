package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("price_source")
public class SourceEntity extends CatalogueRow {
 private String code;
 public String getCode(){return code;} public void setCode(String v){code=v;}
 private String name;
 public String getName(){return name;} public void setName(String v){name=v;}
 private String kind;
 public String getKind(){return kind;} public void setKind(String v){kind=v;}
 private Boolean enabled;
 public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}
