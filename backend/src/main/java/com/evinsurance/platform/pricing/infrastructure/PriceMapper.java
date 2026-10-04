package com.evinsurance.platform.pricing.infrastructure;
import com.evinsurance.platform.pricing.api.PriceFilter;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface PriceMapper {
 long count(@Param("f") PriceFilter filter);
 List<PriceView> list(@Param("f") PriceFilter filter,@Param("offset") int offset,@Param("size") int size);
 PriceView detail(@Param("id") long id);
 PriceRecord record(@Param("id") long id);
 long historyCount(@Param("id") long recordId);
 List<PriceView> history(@Param("id") long recordId,@Param("offset") int offset,@Param("size") int size);
 void lockKeys(@Param("keys") List<String> keys);
 int ensureRecords(@Param("rows") String rows,@Param("actor") long actor);
 List<LatestPrice> latest(@Param("rows") String rows);
 List<PriceVersion> apply(@Param("rows") String rows,@Param("actor") long actor,@Param("roles") String roles,@Param("trace") String trace,@Param("batchId") Long batchId);
 List<ResolvedImportRow> resolve(@Param("rows") String rows);
}
