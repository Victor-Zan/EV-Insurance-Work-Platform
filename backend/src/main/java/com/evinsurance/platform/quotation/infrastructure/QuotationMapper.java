package com.evinsurance.platform.quotation.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.*;
import java.math.BigDecimal;
import com.evinsurance.platform.quotation.infrastructure.QuotationRows.*;
import com.evinsurance.platform.quotation.domain.QuoteCalculator.AllocatedLine;
@Mapper
public interface QuotationMapper {
    Context context(UUID id);
    void ensure(UUID id);
    int save(Context context);
    int nextVersion(@Param("id") UUID id,@Param("type") String type);
    Raw raw(UUID id);
    List<RawItem> rawItems(UUID id);
    void insertRaw(@Param("id") UUID id,@Param("caseId") UUID caseId,@Param("version") int version,@Param("shop") long shop,@Param("assignment") int assignment,@Param("total") BigDecimal total,@Param("actor") long actor);
    void insertRawItem(@Param("id") UUID id,@Param("line") int line,@Param("description") String description,@Param("quantity") int quantity,@Param("unit") BigDecimal unit,@Param("amount") BigDecimal amount,@Param("snapshot") String snapshot);
    Formal formal(UUID id);
    List<FormalItem> formalItems(UUID id);
    void insertFormal(@Param("id") UUID id,@Param("caseId") UUID caseId,@Param("version") int version,@Param("raw") UUID raw,@Param("mode") String mode,@Param("value") BigDecimal value,@Param("original") BigDecimal original,@Param("markup") BigDecimal markup,@Param("total") BigDecimal total,@Param("actor") long actor);
    void insertFormalItem(@Param("id") UUID id,@Param("line") AllocatedLine line);
    Assessment assessment(UUID id);
    void insertAssessment(@Param("id") UUID id,@Param("caseId") UUID caseId,@Param("version") int version,@Param("formal") UUID formal,@Param("amount") BigDecimal amount,@Param("snapshot") String snapshot,@Param("actor") long actor);
    List<Map<String,Object>> activeLossFiles(UUID id);
    int confirmation(@Param("id") UUID id,@Param("c") Context context,@Param("kind") String kind,@Param("snapshot") String snapshot,@Param("actor") long actor);
    void event(@Param("id") UUID id,@Param("basis") int basis,@Param("kind") String kind,@Param("summary") String summary,@Param("actor") long actor);
    Map<String,Object> command(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key);
    void insertCommand(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("hash") String hash,@Param("response") String response);
    long historyCount(@Param("id") UUID id,@Param("type") String type,@Param("shop") Long shop,@Param("assignment") Integer assignment);
    List<Map<String,Object>> history(@Param("id") UUID id,@Param("type") String type,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("offset") int offset,@Param("size") int size);
}
