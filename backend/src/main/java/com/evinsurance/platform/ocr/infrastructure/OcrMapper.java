package com.evinsurance.platform.ocr.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface OcrMapper {
    OcrRow find(UUID id);
    OcrRow lock(UUID id);
    OcrRow byFile(@Param("fileId") UUID fileId,@Param("provider") String provider);
    void insert(@Param("id") UUID id,@Param("fileId") UUID fileId,@Param("intakeId") UUID intakeId,@Param("actor") long actor,@Param("fail") boolean fail,@Param("provider") String provider,@Param("api") String api,@Param("version") String version);
    int retry(UUID id);
    int expire(int maxAttempts);
    OcrRow claim(@Param("token") UUID token,@Param("seconds") int seconds,@Param("maxAttempts") int maxAttempts,@Param("provider") String provider);
    int finish(@Param("id") UUID id,@Param("token") UUID token,@Param("candidate") String candidate,@Param("error") String error);
    int review(@Param("id") UUID id,@Param("version") int version,@Param("candidate") String candidate);
    void appendReview(@Param("id") UUID id,@Param("version") int version,@Param("candidate") String candidate,@Param("actor") long actor,@Param("key") String key);
    Map<String,Object> reviewCommand(@Param("id") UUID id,@Param("key") String key);
    int confirm(@Param("id") UUID id,@Param("version") int version,@Param("actor") long actor);
    long confirmed(@Param("id") UUID id,@Param("version") int version);
    long historyCount(UUID id);
    List<Map<String,Object>> history(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
}
