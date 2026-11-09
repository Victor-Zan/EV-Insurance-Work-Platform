package com.evinsurance.platform.pricing.infrastructure;

import com.evinsurance.platform.pricing.api.SampleRequests;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SampleMapper {
    void lockSource(@Param("key") String key);
    Map<String,Object> existing(@Param("kind") String kind,@Param("key") String key);
    void batch(@Param("id") UUID id,@Param("kind") String kind,@Param("key") String key,
        @Param("name") String name,@Param("objectKey") String objectKey,@Param("actor") long actor);
    void samples(@Param("batch") UUID batch,@Param("rows") String rows);
    Map<String,Object> lock(@Param("id") UUID id);
    String detail(@Param("id") UUID id);
    List<String> relatedHistoricalQuotes(@Param("id") UUID id);
    long count(@Param("f") SampleRequests.Filter filter);
    List<String> list(@Param("f") SampleRequests.Filter filter,@Param("offset") int offset,@Param("size") int size);
    long groupCount(@Param("f") SampleRequests.Filter filter);
    List<String> groups(@Param("f") SampleRequests.Filter filter,@Param("offset") int offset,@Param("size") int size);
    long historyCount(@Param("id") UUID id);
    List<String> history(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    boolean applicable(@Param("part") long part,@Param("model") long model,@Param("unit") String unit);
    void review(@Param("id") UUID id,@Param("r") SampleRequests.Review review,@Param("amount") BigDecimal amount,@Param("actor") long actor);
    void advance(@Param("id") UUID id);
    Map<String,Object> batchDetail(@Param("id") UUID id);
    List<Long> brandsNamed(@Param("name") String name);
    Long historicalModel(@Param("brand") long brand);
    List<Long> partsNamed(@Param("name") String name,@Param("unit") String unit);
    void historicalReviews(@Param("rows") String rows,@Param("actor") long actor);
    void advanceBatch(@Param("batch") UUID batch);
}
