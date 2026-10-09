package com.evinsurance.platform.document.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface FileMapper {
    FileRow find(UUID id);
    void insert(FileRow row);
    int changeState(@Param("id") UUID id,@Param("state") String state);
    long activeCount(@Param("orderId") UUID orderId,@Param("category") String category);
    List<FileRow> list(@Param("orderId") UUID orderId,@Param("offset") int offset,@Param("limit") int limit,
        @Param("scope") String scope,@Param("shopId") Long shopId,@Param("assignmentVersion") Integer assignmentVersion);
    long count(@Param("orderId") UUID orderId,@Param("scope") String scope,@Param("shopId") Long shopId,@Param("assignmentVersion") Integer assignmentVersion);
    Map<String,Object> command(@Param("actor") long actor,@Param("key") String key);
    void insertCommand(@Param("actor") long actor,@Param("key") String key,@Param("hash") String hash,@Param("fileId") UUID fileId);
    String missingReason(UUID orderId);
    void missing(@Param("orderId") UUID orderId,@Param("actor") long actor,@Param("reason") String reason);
}
