package com.evinsurance.platform.pricing.infrastructure;
import java.util.UUID;import java.time.Instant;import com.baomidou.mybatisplus.annotation.*;
@TableName("price_import_batch")
public class ImportBatchEntity {
 @TableId(type=IdType.AUTO)
 private Long id;
 public Long getId(){return id;} public void setId(Long v){id=v;}
 private UUID previewId;
 public UUID getPreviewId(){return previewId;} public void setPreviewId(UUID v){previewId=v;}
 private String fileName;
 public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
 private String source;
 public String getSource(){return source;} public void setSource(String v){source=v;}
 private String sha256;
 public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
 private String status;
 public String getStatus(){return status;} public void setStatus(String v){status=v;}
 private Integer totalRows;
 public Integer getTotalRows(){return totalRows;} public void setTotalRows(Integer v){totalRows=v;}
 private Integer successCount;
 public Integer getSuccessCount(){return successCount;} public void setSuccessCount(Integer v){successCount=v;}
 private Integer failureCount;
 public Integer getFailureCount(){return failureCount;} public void setFailureCount(Integer v){failureCount=v;}
 private Long actorId;
 public Long getActorId(){return actorId;} public void setActorId(Long v){actorId=v;}
 private Instant createdAt;
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
 private Instant completedAt;
 public Instant getCompletedAt(){return completedAt;} public void setCompletedAt(Instant v){completedAt=v;}
}
