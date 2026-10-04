package com.evinsurance.platform.pricing.infrastructure;
import java.util.*;import org.apache.ibatis.annotations.*;
@Mapper public interface ImportMapper {
 record Row(int rowNumber,String raw,String result){}
 record ErrorRow(int rowNumber,String field,String reason,String originalValue){}
 ImportPreviewEntity lock(@Param("id") UUID id);
 int rows(@Param("id") UUID id,@Param("rows") List<Row> rows);
 List<StoredImportRow> stored(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
 int consume(@Param("id") UUID id);
 ImportBatchEntity byPreview(@Param("id") UUID id);
 int errors(@Param("id") long batchId,@Param("errors") List<ErrorRow> errors);
}
