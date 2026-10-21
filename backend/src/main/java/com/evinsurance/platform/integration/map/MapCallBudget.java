package com.evinsurance.platform.integration.map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared, fail-closed request ledger. No key, plaintext address or coordinate is persisted. */
@Service
public class MapCallBudget {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final String run;
    public MapCallBudget(JdbcTemplate db,PlatformTransactionManager manager,Environment env){
        this.db=db;this.run=env.getProperty("AMAP_BUDGET_RUN_ID","");
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Availability(boolean callsEnabled,int remaining,String reason){}
    public Availability availability(){
        if(run.isBlank())return new Availability(false,0,"未配置真实调用预算");
        var rows=db.queryForList("SELECT enabled FROM map_call_budget_run WHERE id=?",Boolean.class,run);
        if(rows.isEmpty()||!rows.getFirst())return new Availability(false,0,"本轮真实调用预算已关闭");
        int remaining=db.queryForObject("SELECT count(*) FROM map_call_budget_slot WHERE run_id=? AND attempted_at IS NULL AND request_hash IS NOT NULL",Integer.class,run);
        return new Availability(remaining>0,remaining,remaining>0?"按已授权的类别及地点限额调用":"本轮真实调用预算已用完");
    }
    public void reserve(String path,Map<String,String> parameters){
        if(run.isBlank())throw MapFailure.of("LOCAL_BUDGET");
        String digest=fingerprint(path,parameters);
        transaction.executeWithoutResult(status->{
            var enabled=db.queryForList("SELECT enabled FROM map_call_budget_run WHERE id=? FOR UPDATE",Boolean.class,run);
            if(enabled.isEmpty()||!enabled.getFirst())throw MapFailure.of("LOCAL_BUDGET");
            int changed=db.update("UPDATE map_call_budget_slot SET attempted_at=CURRENT_TIMESTAMP WHERE run_id=? AND operation=? AND request_hash=? AND attempted_at IS NULL",run,operation(path),digest);
            if(changed!=1)throw MapFailure.of("LOCAL_BUDGET");
        }); // Durable commit MUST finish before the caller opens the network connection.
    }
    public void stopFor(String code){
        if(!run.isBlank()&&Set.of("MAP_CREDENTIALS","MAP_PERMISSION","MAP_QUOTA","MAP_RATE_LIMIT").contains(code))
            transaction.executeWithoutResult(status->db.update("UPDATE map_call_budget_run SET enabled=false WHERE id=?",run));
    }
    public static String operation(String path){return switch(path){
        case "/v3/geocode/geo" -> "GEOCODE";
        case "/v3/geocode/regeo" -> "REVERSE";
        case "/v5/direction/driving" -> "DRIVING";
        default -> throw MapFailure.of("UNSUPPORTED_OPERATION");
    };}
    public static String fingerprint(String path,Map<String,String> parameters){
        try{
            var text=new StringBuilder(path);
            new TreeMap<>(parameters).forEach((key,value)->text.append('\n').append(key).append('=').append(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
