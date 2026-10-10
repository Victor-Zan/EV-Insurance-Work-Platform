package com.evinsurance.platform.complaint.infrastructure;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ComplaintMapper {
    @Select("SELECT * FROM complaint WHERE id=#{id}") Map<String,Object> find(UUID id);
    @Select("SELECT * FROM complaint WHERE id=#{id} FOR UPDATE") Map<String,Object> lock(UUID id);
    @Insert("INSERT INTO complaint(id,work_order_id,owner_id,shop_id,assignment_version,description,photo_snapshot) VALUES(#{id},#{caseId},#{owner},#{shop},#{assignment},#{description},#{snapshot})")
    void create(@Param("id") UUID id,@Param("caseId") UUID caseId,@Param("owner") long owner,@Param("shop") long shop,@Param("assignment") int assignment,@Param("description") String description,@Param("snapshot") String snapshot);
    @Update("UPDATE complaint SET status=#{status},version=version+1 WHERE id=#{id} AND version=#{version}")
    int advance(@Param("id") UUID id,@Param("version") int version,@Param("status") String status);
    @Insert("INSERT INTO complaint_event(complaint_id,kind,public_note,internal_note,actor_id) VALUES(#{id},#{kind},#{note},#{internal},#{actor})")
    void event(@Param("id") UUID id,@Param("kind") String kind,@Param("note") String note,@Param("internal") String internal,@Param("actor") long actor);
    long count(@Param("caseId") UUID caseId,@Param("shop") Long shop,@Param("assignment") Integer assignment);
    List<Map<String,Object>> list(@Param("caseId") UUID caseId,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT count(*) FROM complaint_event WHERE complaint_id=#{id}") long historyCount(UUID id);
    @Select("SELECT id,kind,public_note,internal_note,created_at FROM complaint_event WHERE complaint_id=#{id} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> history(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT request_hash,response_json FROM complaint_command WHERE actor_id=#{actor} AND operation=#{operation} AND command_key=#{key}")
    Map<String,Object> command(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key);
    @Insert("INSERT INTO complaint_command VALUES(#{actor},#{operation},#{key},#{hash},#{response})")
    void commandSave(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("hash") String hash,@Param("response") String response);
}
