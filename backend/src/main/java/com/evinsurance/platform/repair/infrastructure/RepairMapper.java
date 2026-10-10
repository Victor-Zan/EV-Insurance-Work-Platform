package com.evinsurance.platform.repair.infrastructure;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RepairMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))") int lockCommand(String key);
    @Select("SELECT version FROM repair_context WHERE work_order_id=#{id}") Integer version(UUID id);
    @Insert("INSERT INTO repair_context(work_order_id) VALUES(#{id}) ON CONFLICT DO NOTHING") void ensure(UUID id);
    @Update("UPDATE repair_context SET version=version+1 WHERE work_order_id=#{id} AND version=#{version}")
    int advance(@Param("id") UUID id,@Param("version") int version);
    boolean started(UUID id);
    boolean shopUploaded(@Param("id") UUID id,@Param("actor") long actor);
    @Insert("INSERT INTO repair_receipt(work_order_id,completion_id,actor_id,actor_role) VALUES(#{id},#{completion},#{actor},#{role})")
    void receipt(@Param("id") UUID id,@Param("completion") UUID completion,@Param("actor") long actor,@Param("role") String role);
    @Select("SELECT id,actor_role,created_at FROM repair_receipt WHERE work_order_id=#{id} ORDER BY id DESC LIMIT 1") Map<String,Object> receiptLatest(UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM repair_receipt_withdrawal WHERE receipt_id=#{id})") boolean withdrawn(long id);
    @Insert("INSERT INTO repair_receipt_withdrawal(receipt_id,reason,actor_id) VALUES(#{id},#{reason},#{actor})")
    void withdraw(@Param("id") long id,@Param("reason") String reason,@Param("actor") long actor);
    @Select("SELECT id,receipt_id,review_text,score,created_at FROM repair_review WHERE work_order_id=#{id}") Map<String,Object> review(UUID id);
    @Select("SELECT id,receipt_id,review_text,score,reason,created_at FROM repair_review_revision WHERE review_id=#{id} ORDER BY id DESC LIMIT 1") Map<String,Object> revision(UUID id);
    @Insert("INSERT INTO repair_review(id,work_order_id,receipt_id,owner_id,review_text,score) VALUES(#{record},#{id},#{receipt},#{actor},#{text},#{score})")
    void reviewSave(@Param("record") UUID record,@Param("id") UUID id,@Param("receipt") long receipt,@Param("actor") long actor,@Param("text") String text,@Param("score") Integer score);
    @Insert("INSERT INTO repair_review_revision(review_id,receipt_id,review_text,score,reason,actor_id) VALUES(#{id},#{receipt},#{text},#{score},#{reason},#{actor})")
    void revisionSave(@Param("id") UUID id,@Param("receipt") long receipt,@Param("text") String text,@Param("score") Integer score,@Param("reason") String reason,@Param("actor") long actor);
    @Select("SELECT count(*) FROM repair_review_revision WHERE review_id=#{id}") long revisionCount(UUID id);
    @Select("SELECT id,receipt_id,review_text,score,reason,created_at FROM repair_review_revision WHERE review_id=#{id} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> revisions(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT EXISTS(SELECT 1 FROM repair_completion_file WHERE file_id=#{id})") boolean frozen(UUID id);
    @Insert("INSERT INTO repair_progress(id,work_order_id,shop_id,assignment_version,note,photo_snapshot,created_by) VALUES(#{record},#{id},#{shop},#{assignment},#{note},#{snapshot},#{actor})")
    void progress(@Param("record") UUID record,@Param("id") UUID id,@Param("shop") long shop,@Param("assignment") int assignment,@Param("note") String note,@Param("snapshot") String snapshot,@Param("actor") long actor);
    @Insert("INSERT INTO repair_completion(id,work_order_id,shop_id,assignment_version,photo_snapshot,created_by) VALUES(#{record},#{id},#{shop},#{assignment},#{snapshot},#{actor})")
    void complete(@Param("record") UUID record,@Param("id") UUID id,@Param("shop") long shop,@Param("assignment") int assignment,@Param("snapshot") String snapshot,@Param("actor") long actor);
    @Insert("INSERT INTO repair_completion_file VALUES(#{completion},#{file},#{version})")
    void evidence(@Param("completion") UUID completion,@Param("file") UUID file,@Param("version") int version);
    @Select("SELECT id,shop_id,assignment_version,photo_snapshot,created_at FROM repair_completion WHERE work_order_id=#{id}") Map<String,Object> completion(UUID id);
    long count(@Param("id") UUID id,@Param("shop") Long shop,@Param("assignment") Integer assignment);
    List<Map<String,Object>> history(@Param("id") UUID id,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("size") int size,@Param("offset") int offset);
    @Select("SELECT request_hash,response_json FROM repair_command WHERE actor_id=#{actor} AND operation=#{operation} AND command_key=#{key}")
    Map<String,Object> command(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key);
    @Insert("INSERT INTO repair_command VALUES(#{actor},#{operation},#{key},#{hash},#{response})")
    void commandSave(@Param("actor") long actor,@Param("operation") String operation,@Param("key") String key,@Param("hash") String hash,@Param("response") String response);
}
