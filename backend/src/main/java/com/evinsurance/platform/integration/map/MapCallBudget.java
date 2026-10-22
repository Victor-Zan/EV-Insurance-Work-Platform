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
    private final String runs,slots;
    private final String ledgerSchema,extraAttempts;
    public MapCallBudget(JdbcTemplate db,PlatformTransactionManager manager,Environment env){
        this.db=db;this.run=env.getProperty("AMAP_BUDGET_RUN_ID","");
        // An isolated business schema can use the original ledger without copying
        // or resetting any allowances. Only a trusted server setting selects it.
        String schema=env.getProperty("AMAP_BUDGET_SCHEMA","");
        if(!schema.isEmpty()&&!schema.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid Amap budget schema");
        String prefix=schema.isEmpty()?"":"\""+schema+"\".";
        runs=prefix+"map_call_budget_run";slots=prefix+"map_call_budget_slot";
        ledgerSchema=schema;
        String extraSchema=env.getProperty("AMAP_BUDGET_EXTRA_SCHEMA","");
        if(!extraSchema.isEmpty()&&(!extraSchema.matches("[a-z][a-z0-9_]{0,62}")||schema.isEmpty()))throw new IllegalArgumentException("Extra Amap attempts require explicit valid ledger schemas");
        extraAttempts=extraSchema.isEmpty()?null:"\""+extraSchema+"\".map_call_budget_extra_attempt";
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Availability(boolean callsEnabled,int remaining,String reason){}
    public Availability availability(){
        if(run.isBlank())return new Availability(false,0,"未配置真实调用预算");
        var rows=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=?",Boolean.class,run);
        if(rows.isEmpty()||!rows.getFirst())return new Availability(false,0,"本轮真实调用预算已关闭");
        int remaining=db.queryForObject("SELECT count(*) FROM "+slots+" WHERE run_id=? AND attempted_at IS NULL AND request_hash IS NOT NULL",Integer.class,run);
        if(extraAttempts!=null)remaining+=db.queryForObject("SELECT count(*) "+eligibleExtras()+" AND e.attempted_at IS NULL",Integer.class,ledgerSchema,run);
        return new Availability(remaining>0,remaining,remaining>0?"按已授权的类别及地点限额调用":"本轮真实调用预算已用完");
    }
    public void reserve(String path,Map<String,String> parameters){
        if(run.isBlank())throw MapFailure.of("LOCAL_BUDGET");
        String digest=fingerprint(path,parameters);
        transaction.executeWithoutResult(status->{
            var enabled=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=? FOR UPDATE",Boolean.class,run);
            if(enabled.isEmpty()||!enabled.getFirst())throw MapFailure.of("LOCAL_BUDGET");
            int changed=db.update("UPDATE "+slots+" SET attempted_at=CURRENT_TIMESTAMP WHERE run_id=? AND operation=? AND request_hash=? AND attempted_at IS NULL",run,operation(path),digest);
            if(changed==0&&extraAttempts!=null)changed=db.update("UPDATE "+extraAttempts+" SET attempted_at=CURRENT_TIMESTAMP WHERE id=(SELECT e.id "+eligibleExtras()+" AND e.operation=? AND e.request_hash=? AND e.attempted_at IS NULL ORDER BY e.id LIMIT 1)",ledgerSchema,run,operation(path),digest);
            if(changed!=1)throw MapFailure.of("LOCAL_BUDGET");
        }); // Durable commit MUST finish before the caller opens the network connection.
    }
    private String eligibleExtras(){
        return "FROM "+extraAttempts+" e JOIN "+slots+" s ON s.run_id=e.run_id AND s.subject=e.subject AND s.operation=e.operation AND s.request_hash=e.request_hash WHERE e.ledger_schema=? AND e.run_id=? AND s.attempted_at IS NOT NULL";
    }
    public void stopFor(String code){
        if(!run.isBlank()&&Set.of("MAP_CREDENTIALS","MAP_PERMISSION","MAP_QUOTA","MAP_RATE_LIMIT").contains(code))
            transaction.executeWithoutResult(status->db.update("UPDATE "+runs+" SET enabled=false WHERE id=?",run));
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
