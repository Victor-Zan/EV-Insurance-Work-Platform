package com.evinsurance.platform.funds.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface FundsImportMapper {
    record PreviewRow(int number,String raw,UUID caseId,String status,String error) {}
    List<Map<String,Object>> matches(@Param("claims") String claims,@Param("businesses") String businesses);
    void insertRows(@Param("id") UUID id,@Param("rows") List<PreviewRow> rows);
    @Insert("INSERT INTO funds_import(id,actor_id,file_name,file_format,sha256,object_key,total_rows) VALUES(#{id},#{actor},#{name},#{format},#{hash},#{key},#{count})") void insertBatch(@Param("id") UUID id,@Param("actor") long actor,@Param("name") String name,@Param("format") String format,@Param("hash") String hash,@Param("key") String key,@Param("count") int count);
    @Select("SELECT * FROM funds_import WHERE id=#{id}") Map<String,Object> findBatch(UUID id);
    @Select("SELECT count(*) FROM funds_import WHERE actor_id=#{actor}") long count(long actor);
    @Select("SELECT * FROM funds_import WHERE actor_id=#{actor} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> batches(@Param("actor") long actor,@Param("offset") int offset,@Param("size") int size);
    @Insert("INSERT INTO funds_import_row(import_id,row_number,raw_json,case_id,status,error_code) VALUES(#{id},#{number},#{raw},#{caseId},#{status},#{error})") void row(@Param("id") UUID id,@Param("number") int number,@Param("raw") String raw,@Param("caseId") UUID caseId,@Param("status") String status,@Param("error") String error);
    @Select("SELECT * FROM funds_import_row WHERE import_id=#{id} ORDER BY row_number LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> rows(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT * FROM funds_import_row WHERE import_id=#{id} AND row_number=#{number} FOR UPDATE") Map<String,Object> lock(@Param("id") UUID id,@Param("number") int number);
    @Select("SELECT row_number FROM funds_import_row WHERE import_id=#{id} AND status IN ('READY','FAILED') ORDER BY row_number") List<Integer> pending(UUID id);
    @Update("UPDATE funds_import_row SET status=#{status},error_code=#{error},entry_id=#{entry},version=version+1 WHERE import_id=#{id} AND row_number=#{number} AND version=#{version}") int result(@Param("id") UUID id,@Param("number") int number,@Param("version") int version,@Param("status") String status,@Param("error") String error,@Param("entry") UUID entry);
    @Update("UPDATE funds_import_row SET case_id=#{caseId},status='READY',error_code=NULL,version=version+1 WHERE import_id=#{id} AND row_number=#{number} AND version=#{version}") int resolve(@Param("id") UUID id,@Param("number") int number,@Param("version") int version,@Param("caseId") UUID caseId);
    @Insert("INSERT INTO funds_import_resolution(import_id,row_number,case_id,reason,actor_id) VALUES(#{id},#{number},#{caseId},#{reason},#{actor})") void resolution(@Param("id") UUID id,@Param("number") int number,@Param("caseId") UUID caseId,@Param("reason") String reason,@Param("actor") long actor);
    @Select("SELECT id FROM work_order WHERE claim_no=#{claim} AND status!='DRAFT' ORDER BY id LIMIT 2") List<UUID> byClaim(String claim);
    @Select("SELECT id FROM work_order WHERE business_no=#{number} AND status!='DRAFT' ORDER BY id LIMIT 2") List<UUID> byBusiness(String number);
    @Select("SELECT * FROM funds_import_resolution WHERE import_id=#{id} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> resolutions(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT count(*) FROM funds_import_resolution WHERE import_id=#{id}") long resolutionCount(UUID id);
}
