package com.evinsurance.platform.notification.infrastructure;
import java.util.*;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface NotificationMapper {
    @Select("SELECT * FROM notification_event WHERE projected_at IS NULL ORDER BY audit_id FOR UPDATE SKIP LOCKED LIMIT 50") List<Map<String,Object>> claim();
    @Update("UPDATE notification_event SET projected_at=now() WHERE audit_id=#{id}") void projected(long id);
    @Insert("INSERT INTO in_app_notification(event_id,recipient_id,recipient_role) VALUES(#{event},#{user},#{role}) ON CONFLICT DO NOTHING") void deliver(@Param("event") long event,@Param("user") long user,@Param("role") String role);
    List<Map<String,Object>> recipients(@Param("id") UUID id,@Param("kind") String kind);
    long count(@Param("user") long user);
    List<Map<String,Object>> inbox(@Param("user") long user,@Param("offset") int offset,@Param("size") int size);
    Map<String,Object> visible(@Param("user") long user,@Param("id") long id);
    @Insert("INSERT INTO notification_read(notification_id,user_id) VALUES(#{id},#{user}) ON CONFLICT DO NOTHING") int read(@Param("id") long id,@Param("user") long user);
    List<Map<String,Object>> todos(@Param("user") long user,@Param("all") boolean all,@Param("caseId") UUID caseId,@Param("key") String key,@Param("offset") int offset,@Param("size") int size);
    long todoCount(@Param("user") long user,@Param("all") boolean all,@Param("caseId") UUID caseId,@Param("key") String key);
    @Insert("INSERT INTO todo_deadline(task_key,work_order_id) VALUES(#{key},#{id}) ON CONFLICT DO NOTHING") void ensure(@Param("key") String key,@Param("id") UUID id);
    @Select("SELECT * FROM todo_deadline WHERE task_key=#{key}") Map<String,Object> deadlineRead(String key);
    @Select("SELECT * FROM todo_deadline WHERE task_key=#{key} FOR UPDATE") Map<String,Object> deadline(String key);
    @Update("UPDATE todo_deadline SET deadline=#{time},version=version+1 WHERE task_key=#{key} AND version=#{version}") int change(@Param("key") String key,@Param("version") int version,@Param("time") Instant time);
    @Insert("INSERT INTO todo_deadline_event(task_key,deadline,reason,actor_id) VALUES(#{key},#{time},#{reason},#{actor})") void event(@Param("key") String key,@Param("time") Instant time,@Param("reason") String reason,@Param("actor") long actor);
    @Select("SELECT count(*) FROM todo_deadline_event WHERE task_key=#{key}") long historyCount(String key);
    @Select("SELECT * FROM todo_deadline_event WHERE task_key=#{key} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}") List<Map<String,Object>> history(@Param("key") String key,@Param("offset") int offset,@Param("size") int size);
}
