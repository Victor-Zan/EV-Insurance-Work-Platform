package com.evinsurance.platform.integration.map;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate opt-in POI_SEARCH ledger. Startup never creates allowances. */
final class PlaceSearchBudget {
    private final JdbcTemplate db;private final TransactionTemplate transaction;
    private final String run,runs,slots;
    PlaceSearchBudget(JdbcTemplate db,PlatformTransactionManager manager,Environment env){
        if(!env.acceptsProfiles(Profiles.of("dev"))||!"AMAP_WEB_V1".equals(env.getProperty("MAP_PROVIDER")))throw new IllegalArgumentException("Place allowance requires explicit local dev Amap mode");
        String schema=env.getProperty("AMAP_PLACE_BUDGET_SCHEMA","");run=env.getProperty("AMAP_PLACE_BUDGET_RUN_ID","");
        if(!schema.matches("amap_place_budget_[a-z0-9_]{1,40}")||!run.matches("[A-Za-z0-9_-]{1,80}")||!env.getProperty("AMAP_BUDGET_RUN_ID","").isBlank())throw new IllegalArgumentException("Invalid separate place allowance identity");
        this.db=db;transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        runs="\""+schema+"\".place_search_run";slots="\""+schema+"\".place_search_slot";
        Flyway.configure().dataSource(Objects.requireNonNull(db.getDataSource())).schemas(schema).defaultSchema(schema).table("flyway_place_budget_history").locations("classpath:db/map-place-budget").load().migrate();
    }
    MapCallBudget.OperationAllowance availability(){
        var open=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=?",Boolean.class,run);
        var row=db.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE attempted_at IS NOT NULL) AS used,count(*) FILTER(WHERE attempted_at IS NULL) AS remaining FROM "+slots+" WHERE run_id=?",run);
        return new MapCallBudget.OperationAllowance("POI_SEARCH",number(row,"total"),number(row,"used"),0,!open.isEmpty()&&open.getFirst()?number(row,"remaining"):0);
    }
    private static int number(Map<String,Object> row,String name){return ((Number)row.get(name)).intValue();}
    void reserve(String path,Map<String,String> parameters){
        if(!"/v5/place/text".equals(path))throw MapFailure.of("UNSUPPORTED_OPERATION");
        transaction.executeWithoutResult(status->{
            var open=db.queryForList("SELECT enabled FROM "+runs+" WHERE id=? FOR UPDATE",Boolean.class,run);
            if(open.isEmpty()||!open.getFirst())throw MapFailure.of("LOCAL_BUDGET");
            var ids=db.queryForList("SELECT id FROM "+slots+" WHERE run_id=? AND attempted_at IS NULL ORDER BY ordinal LIMIT 1 FOR UPDATE",Long.class,run);
            if(ids.isEmpty())throw MapFailure.of("LOCAL_BUDGET");
            db.update("UPDATE "+slots+" SET attempted_at=CURRENT_TIMESTAMP,request_hash=? WHERE id=?",MapCallBudget.fingerprint(path,parameters),ids.getFirst());
        }); // durable attempt precedes HTTP; failures/timeouts never restore the slot
    }
    void stop(){transaction.executeWithoutResult(status->db.update("UPDATE "+runs+" SET enabled=false,closed_at=CURRENT_TIMESTAMP WHERE id=? AND enabled AND closed_at IS NULL",run));}
}
