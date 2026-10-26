package com.evinsurance.platform.integration.map;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomaticDispatchStore {
    private final JdbcTemplate db;
    public AutomaticDispatchStore(JdbcTemplate db){this.db=db;}
    public record Attempt(UUID id,String fingerprint,UUID requestKey,String status,Instant expires){}
    public record Begun(Attempt attempt,boolean created){}
    @Transactional public Begun begin(long actor,UUID caseId,String fingerprint,UUID requestKey,boolean retry){
        UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600);
        boolean inserted=db.update("INSERT INTO automatic_dispatch_attempt(actor_id,work_order_id,id,fingerprint,request_key,status,expires_at) VALUES(?,?,?,?,?,'RUNNING',?) ON CONFLICT(actor_id,work_order_id) DO NOTHING",actor,caseId,id,fingerprint,requestKey,java.sql.Timestamp.from(expires))==1;
        var old=db.queryForObject("SELECT * FROM automatic_dispatch_attempt WHERE actor_id=? AND work_order_id=? FOR UPDATE",(rs,n)->new Attempt(rs.getObject("id",UUID.class),rs.getString("fingerprint"),rs.getObject("request_key",UUID.class),rs.getString("status"),rs.getTimestamp("expires_at").toInstant()),actor,caseId);
        if(inserted)return new Begun(old,true);
        if(old.fingerprint().equals(fingerprint)&&(!retry||old.requestKey().equals(requestKey)||("RUNNING".equals(old.status())&&old.expires().isAfter(Instant.now()))))return new Begun(old,false);
        db.update("UPDATE automatic_dispatch_attempt SET id=?,fingerprint=?,request_key=?,status='RUNNING',expires_at=? WHERE actor_id=? AND work_order_id=?",id,fingerprint,requestKey,java.sql.Timestamp.from(expires),actor,caseId);
        return new Begun(new Attempt(id,fingerprint,requestKey,"RUNNING",expires),true);
    }
    public boolean current(long actor,UUID caseId,UUID id){return db.queryForObject("SELECT count(*) FROM automatic_dispatch_attempt WHERE actor_id=? AND work_order_id=? AND id=?",Long.class,actor,caseId,id)==1;}
    public boolean lockCurrent(long actor,UUID caseId,UUID id){return !db.queryForList("SELECT id FROM automatic_dispatch_attempt WHERE actor_id=? AND work_order_id=? AND id=? FOR UPDATE",actor,caseId,id).isEmpty();}
    public void finish(UUID id,String status){db.update("UPDATE automatic_dispatch_attempt SET status=? WHERE id=?",status,id);}
}
