package com.evinsurance.platform.funds.infrastructure;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface FundsMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))") int commandLock(String key);
    @Select("SELECT version FROM funds_context WHERE work_order_id=#{id}") Integer version(UUID id);
    @Insert("INSERT INTO funds_context(work_order_id) VALUES(#{id}) ON CONFLICT DO NOTHING") void ensure(UUID id);
    @Update("UPDATE funds_context SET version=version+1 WHERE work_order_id=#{id} AND version=#{version}") int advance(@Param("id") UUID id,@Param("version") int version);
    @Select("SELECT * FROM funds_target WHERE work_order_id=#{id} AND direction=#{direction} ORDER BY id DESC LIMIT 1") Map<String,Object> target(@Param("id") UUID id,@Param("direction") String direction);
    @Insert("INSERT INTO funds_target(work_order_id,direction,amount,shop_id,assignment_version,reason,actor_id) VALUES(#{id},#{direction},#{amount},#{shop},#{assignment},#{reason},#{actor})") void targetSave(@Param("id") UUID id,@Param("direction") String direction,@Param("amount") BigDecimal amount,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("reason") String reason,@Param("actor") long actor);
    BigDecimal net(@Param("id") UUID id,@Param("direction") String direction,@Param("shop") Long shop,@Param("assignment") Integer assignment);
    @Select("SELECT * FROM funds_entry WHERE direction=#{direction} AND transaction_no=#{number}") Map<String,Object> transaction(@Param("direction") String direction,@Param("number") String number);
    @Select("SELECT * FROM funds_entry WHERE id=#{id}") Map<String,Object> entry(UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM funds_reversal WHERE entry_id=#{id})") boolean reversed(UUID id);
    @Insert("INSERT INTO funds_entry(id,work_order_id,direction,transaction_no,amount,occurred_at,shop_id,assignment_version,note,content_hash,actor_id) VALUES(#{record},#{id},#{direction},#{number},#{amount},#{time},#{shop},#{assignment},#{note},#{hash},#{actor})") void entrySave(@Param("record") UUID record,@Param("id") UUID id,@Param("direction") String direction,@Param("number") String number,@Param("amount") BigDecimal amount,@Param("time") Instant time,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("note") String note,@Param("hash") String hash,@Param("actor") long actor);
    @Insert("INSERT INTO funds_reversal(id,entry_id,reason,actor_id) VALUES(#{record},#{entry},#{reason},#{actor})") void reverse(@Param("record") UUID record,@Param("entry") UUID entry,@Param("reason") String reason,@Param("actor") long actor);
    @Select("SELECT request_hash,response_json FROM funds_command WHERE actor_id=#{actor} AND operation=#{op} AND command_key=#{key}") Map<String,Object> command(@Param("actor") long actor,@Param("op") String op,@Param("key") String key);
    @Insert("INSERT INTO funds_command VALUES(#{actor},#{op},#{key},#{hash},#{response})") void commandSave(@Param("actor") long actor,@Param("op") String op,@Param("key") String key,@Param("hash") String hash,@Param("response") String response);
    long count(@Param("id") UUID id,@Param("shop") Long shop,@Param("assignment") Integer assignment);
    List<Map<String,Object>> entries(@Param("id") UUID id,@Param("shop") Long shop,@Param("assignment") Integer assignment,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT count(*) FROM funds_target WHERE work_order_id=#{id}") long targetCount(UUID id);
    @Select("SELECT * FROM funds_target WHERE work_order_id=#{id} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> targets(@Param("id") UUID id,@Param("offset") int offset,@Param("size") int size);
    @Select("SELECT EXISTS(SELECT 1 FROM repair_receipt r WHERE r.work_order_id=#{id} AND NOT EXISTS(SELECT 1 FROM repair_receipt_withdrawal w WHERE w.receipt_id=r.id))") boolean received(UUID id);
}
