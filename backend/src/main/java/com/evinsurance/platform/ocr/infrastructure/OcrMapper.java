package com.evinsurance.platform.ocr.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface OcrMapper {
    OcrRow find(UUID id);
    OcrRow lock(UUID id);
    OcrRow byFile(UUID fileId);
    void insert(@Param("id") UUID id,@Param("fileId") UUID fileId,@Param("actor") long actor,@Param("fail") boolean fail);
    int retry(UUID id);
    int expire(int maxAttempts);
    OcrRow claim(@Param("token") UUID token,@Param("seconds") int seconds,@Param("maxAttempts") int maxAttempts);
    int finish(@Param("id") UUID id,@Param("token") UUID token,@Param("candidate") String candidate,@Param("error") String error);
    int review(@Param("id") UUID id,@Param("version") int version,@Param("candidate") String candidate);
    void appendReview(@Param("id") UUID id,@Param("version") int version,@Param("candidate") String candidate,@Param("actor") long actor,@Param("key") String key);
    Map<String,Object> reviewCommand(@Param("id") UUID id,@Param("key") String key);
    int confirm(@Param("id") UUID id,@Param("version") int version,@Param("actor") long actor);
    long confirmed(@Param("id") UUID id,@Param("version") int version);
    long historyCount(UUID id);
    List<Map<String,Object>> history(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
}
