package com.evinsurance.platform.integration.map;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local-development opt-in. Startup never grants or refills calls. */
final class DevelopmentMapBudget {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final String run,runs,slots;
    private final ThreadLocal<UUID> drivingBatch=new ThreadLocal<>();

    DevelopmentMapBudget(JdbcTemplate db,PlatformTransactionManager manager,Environment env){
        if(!env.acceptsProfiles(Profiles.of("dev")) || !"AMAP_WEB_V1".equals(env.getProperty("MAP_PROVIDER")))
            throw new IllegalArgumentException("Controlled map allowance requires explicit dev Amap mode");
        String schema=env.getProperty("AMAP_DEV_BUDGET_SCHEMA","");
        run=env.getProperty("AMAP_DEV_BUDGET_RUN_ID","");
        if(!schema.matches("amap_dev_budget_[a-z0-9_]{1,40}") || !run.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid development map ledger identity");
        if(!env.getProperty("AMAP_BUDGET_RUN_ID","").isEmpty())
            throw new IllegalArgumentException("Development and historical allowances cannot be combined");
        if(env.getProperty("AMAP_MAX_CALLS_PER_PROCESS",Integer.class,0)<1)
            throw new IllegalArgumentException("Development process guard must be explicitly enabled");
        this.db=db;transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String prefix="\""+schema+"\".";runs=prefix+"development_map_run";slots=prefix+"development_map_slot";
        // Own history/location: never upgrades the business schema or old budget tables.
        Flyway.configure().dataSource(Objects.requireNonNull(db.getDataSource())).schemas(schema).defaultSchema(schema)
            .table("flyway_map_budget_history").locations("classpath:db/map-budget").load().migrate();
    }
    MapCallBudget.Availability availability(){
        var enabled=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=?",Boolean.class,run);
        var counts=new ArrayList<MapCallBudget.OperationAllowance>();
        for(String operation:List.of("GEOCODE","REVERSE","DRIVING")){
            var row=db.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE attempted_at IS NOT NULL) AS used,count(*) FILTER(WHERE attempted_at IS NULL AND reserved_by IS NOT NULL) AS reserved,count(*) FILTER(WHERE attempted_at IS NULL AND reserved_by IS NULL) AS remaining FROM "+slots+" WHERE run_id=? AND operation=?",run,operation);
            counts.add(new MapCallBudget.OperationAllowance(operation,number(row,"total"),number(row,"used"),number(row,"reserved"),number(row,"remaining")));
        }
        int remaining=counts.stream().mapToInt(MapCallBudget.OperationAllowance::remaining).sum();
        boolean open=!enabled.isEmpty()&&enabled.getFirst();
        return new MapCallBudget.Availability(open&&remaining>0,remaining,!open?"开发测试调用预算未配置或已关闭":remaining==0?"开发测试调用预算已用完":"受控开发测试额度；失败和超时计次，重启不补额度",List.copyOf(counts));
    }
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private void lockRun(){
        var enabled=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=? FOR UPDATE",Boolean.class,run);
        if(enabled.isEmpty()||!enabled.getFirst())throw MapFailure.of("LOCAL_BUDGET");
    }
    void reserve(String path,Map<String,String> parameters){
        String operation=MapCallBudget.operation(path),hash=MapCallBudget.fingerprint(path,parameters);UUID batch=drivingBatch.get();
        if("DRIVING".equals(operation)&&batch==null)throw MapFailure.of("LOCAL_BUDGET");
        transaction.executeWithoutResult(status->{
            lockRun();
            String condition="DRIVING".equals(operation)&&batch!=null?"reserved_by=?":"reserved_by IS NULL";
            var args=new ArrayList<Object>(List.of(run,operation));if("DRIVING".equals(operation)&&batch!=null)args.add(batch);
            var ids=db.queryForList("SELECT id FROM "+slots+" WHERE run_id=? AND operation=? AND attempted_at IS NULL AND "+condition+" ORDER BY id LIMIT 1 FOR UPDATE",Long.class,args.toArray());
            if(ids.isEmpty())throw MapFailure.of("LOCAL_BUDGET");
            db.update("UPDATE "+slots+" SET request_hash=?,attempted_at=CURRENT_TIMESTAMP WHERE id=?",hash,ids.getFirst());
        }); // committed before any HTTP; failure/timeout cannot give this slot back
    }
    AutoCloseable reserveDriving(int count){
        if(count<0||count>50||drivingBatch.get()!=null)throw new IllegalArgumentException("Invalid route batch");
        UUID batch=UUID.randomUUID();
        transaction.executeWithoutResult(status->{
            lockRun();var ids=db.queryForList("SELECT id FROM "+slots+" WHERE run_id=? AND operation='DRIVING' AND attempted_at IS NULL AND reserved_by IS NULL ORDER BY id LIMIT ? FOR UPDATE",Long.class,run,count);
            if(ids.size()!=count)throw MapFailure.of("LOCAL_BUDGET");
            for(long id:ids)db.update("UPDATE "+slots+" SET reserved_by=?,reserved_at=CURRENT_TIMESTAMP WHERE id=?",batch,id);
        });
        drivingBatch.set(batch);
        return ()->{try{transaction.executeWithoutResult(status->{lockRunForRelease();db.update("UPDATE "+slots+" SET reserved_by=NULL,reserved_at=NULL WHERE run_id=? AND reserved_by=? AND attempted_at IS NULL",run,batch);});}finally{drivingBatch.remove();}};
    }
    private void lockRunForRelease(){db.queryForList("SELECT id FROM "+runs+" WHERE id=? FOR UPDATE",String.class,run);}
    void stop(){transaction.executeWithoutResult(status->db.update("UPDATE "+runs+" SET enabled=false,closed_at=CURRENT_TIMESTAMP WHERE id=? AND closed_at IS NULL",run));}
}
