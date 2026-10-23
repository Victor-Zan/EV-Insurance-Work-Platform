package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;

/** Synthetic requests only; no HTTP client or vendor credential is used. */
class DevelopmentMapBudgetPostgresIT {
    private JdbcTemplate db;private DataSourceTransactionManager manager;private MockEnvironment env;private MapCallBudget ledger;private String schema;
    @BeforeEach void setup(){
        String url=System.getenv("TEST_DB_URL");if(url==null)throw new IllegalStateException("Isolated PostgreSQL required");
        db=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("TEST_DB_USERNAME"),System.getenv("TEST_DB_PASSWORD")));manager=new DataSourceTransactionManager(db.getDataSource());
        schema="amap_dev_budget_"+UUID.randomUUID().toString().replace("-","");
        env=new MockEnvironment();env.setActiveProfiles("dev");
        env.withProperty("MAP_PROVIDER","AMAP_WEB_V1").withProperty("AMAP_MAX_CALLS_PER_PROCESS","35").withProperty("AMAP_DEV_BUDGET_ENABLED","true").withProperty("AMAP_DEV_BUDGET_SCHEMA",schema).withProperty("AMAP_DEV_BUDGET_RUN_ID","synthetic-approved");
        ledger=new MapCallBudget(db,manager,env);
    }
    private void provision(){
        db.update("INSERT INTO "+schema+".development_map_run(id,authorization_ref,enabled,geocode_limit,reverse_limit,driving_limit) VALUES('synthetic-approved','offline-test-only',true,10,5,20) ON CONFLICT DO NOTHING");
        for(var pair:Map.of("GEOCODE",10,"REVERSE",5,"DRIVING",20).entrySet())
            for(int i=1;i<=pair.getValue();i++)db.update("INSERT INTO "+schema+".development_map_slot(run_id,operation,ordinal) VALUES('synthetic-approved',?,?) ON CONFLICT DO NOTHING",pair.getKey(),i);
    }
    private MapCallBudget.OperationAllowance count(String operation){return ledger.availability().operations().stream().filter(row->row.operation().equals(operation)).findFirst().orElseThrow();}
    @Test void migrationAndRestartNeverGrantCallsAndRepeatedAddressesConsumeSeparateSlots() throws Exception {
        assertThat(ledger.availability().callsEnabled()).isFalse();assertThat(ledger.availability().remaining()).isZero();
        provision();assertThat(ledger.availability().remaining()).isEqualTo(35);
        var successes=new AtomicInteger();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<16;i++)jobs.add(pool.submit(()->{try{ledger.reserve("/v3/geocode/geo",Map.of("address","synthetic-address","city","440300"));successes.incrementAndGet();}catch(MapFailure failure){assertThat(failure.code()).isEqualTo("MAP_LOCAL_BUDGET");}}));
            for(var job:jobs)job.get();
        }
        assertThat(successes.get()).isEqualTo(10);assertThat(count("GEOCODE").used()).isEqualTo(10);assertThat(count("REVERSE").remaining()).isEqualTo(5);
        ledger=new MapCallBudget(db,manager,env);provision();assertThat(count("GEOCODE").remaining()).isZero();
        assertThatThrownBy(()->db.update("UPDATE "+schema+".development_map_slot SET attempted_at=NULL,request_hash=NULL WHERE operation='GEOCODE'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("INSERT INTO "+schema+".development_map_slot(run_id,operation,ordinal) VALUES('synthetic-approved','GEOCODE',11)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE "+schema+".development_map_run SET geocode_limit=11")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void allRouteSlotsReservedBeforeAnyCallAndUnusedNormalReservationsReleased(){
        provision();assertThatThrownBy(()->ledger.reserveDriving(21)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");assertThat(count("DRIVING").remaining()).isEqualTo(20);
        try(var routes=ledger.reserveDriving(3)){
            assertThat(count("DRIVING").reserved()).isEqualTo(3);
            var concurrent=new MapCallBudget(db,manager,env);
            assertThatThrownBy(()->concurrent.reserveDriving(18)).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
            ledger.reserve("/v5/direction/driving",Map.of("origin","113,23","destination","113.1,23.1","strategy","32"));
            assertThat(count("DRIVING").used()).isEqualTo(1);
        }
        assertThat(count("DRIVING").remaining()).isEqualTo(19);assertThat(count("DRIVING").reserved()).isZero();
        assertThatThrownBy(()->ledger.reserve("/v5/direction/driving",Map.of())).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
    }
    @Test void crashReservationsFailClosedAndSupplierFailuresClosePersistently(){
        provision();ledger.reserveDriving(2);var restarted=new MapCallBudget(db,manager,env);
        assertThat(restarted.availability().operations().stream().filter(row->row.operation().equals("DRIVING")).findFirst().orElseThrow().reserved()).isEqualTo(2);
        restarted.stopFor("MAP_QUOTA");assertThat(restarted.availability().callsEnabled()).isFalse();
        assertThatThrownBy(()->restarted.reserve("/v3/geocode/regeo",Map.of())).extracting("code").isEqualTo("MAP_LOCAL_BUDGET");
        assertThatThrownBy(()->db.update("UPDATE "+schema+".development_map_run SET enabled=true,closed_at=NULL")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void developmentAllowanceCannotEnableInProductionOrCombineHistoricalRun(){
        env.setActiveProfiles("prod");assertThatThrownBy(()->new MapCallBudget(db,manager,env)).isInstanceOf(IllegalArgumentException.class);
        env.setActiveProfiles("dev");env.withProperty("AMAP_BUDGET_RUN_ID","old-acceptance");assertThatThrownBy(()->new MapCallBudget(db,manager,env)).isInstanceOf(IllegalArgumentException.class);
    }
}
