package com.evinsurance.platform.pricing.infrastructure;
import java.util.UUID;import java.time.Instant;import com.baomidou.mybatisplus.annotation.*;
@TableName("price_import_preview")
public class ImportPreviewEntity {
 @TableId(type=IdType.INPUT)
 private UUID id;
 public UUID getId(){return id;} public void setId(UUID v){id=v;}
 private Long actorId;
 public Long getActorId(){return actorId;} public void setActorId(Long v){actorId=v;}
 private String fileName;
 public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
 private String fileFormat;
 public String getFileFormat(){return fileFormat;} public void setFileFormat(String v){fileFormat=v;}
 private String sha256;
 public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
 private Instant createdAt;
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 private Instant expiresAt;
 public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
 private Instant consumedAt;
 public Instant getConsumedAt(){return consumedAt;} public void setConsumedAt(Instant v){consumedAt=v;}
 private Integer totalRows;
 public Integer getTotalRows(){return totalRows;} public void setTotalRows(Integer v){totalRows=v;}
}
