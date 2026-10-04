package com.evinsurance.platform.pricing.infrastructure;
import com.baomidou.mybatisplus.annotation.TableName;
@TableName("price_alias")
public class AliasEntity extends CatalogueRow {
 private Long partId;
 public Long getPartId(){return partId;} public void setPartId(Long v){partId=v;}
 private String name;
 public String getName(){return name;} public void setName(String v){name=v;}
}
