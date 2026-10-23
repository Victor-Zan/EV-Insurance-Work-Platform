package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only new isolated schemas: validates shared V24 -> additive map migrations. */
class MapMigrationPostgresIT {
    @Test void additiveMigrationPreservesExistingPositionsAndNeverInventsQueryVersionsOrBudgets() {
        String url=System.getenv("TEST_DB_URL"),user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        if(url==null||user==null||password==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        String schema="map_migration_it_"+UUID.randomUUID().toString().replace("-","");
        String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        var placeholders=java.util.Map.of("devAdminHash",hash,"devCustomerServiceHash",hash,"devRepairShopHash",hash,"devOwnerHash",hash);
        var before=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration","classpath:db/dev").placeholders(placeholders).target("24").load();
        before.migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));
        long actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        long shop=db.queryForObject("select id from repair_shop where code='DEV-SHOP'",Long.class);
        long district=db.queryForObject("select id from region where code='DEV-DISTRICT'",Long.class);
        db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,longitude,latitude,coordinate_system,source,created_by) values(?,1,?,'合成旧位置',113.1,23.1,'GCJ-02','ADMIN_CONFIRMED_GCJ02',?)",shop,district,actor);
        long brand=db.queryForObject("insert into price_brand(code,name,created_by,updated_by) values('SYNTH','合成品牌',?,?) returning id",Long.class,actor,actor);
        long model=db.queryForObject("insert into price_model(brand_id,code,name,created_by,updated_by) values(?,'SYNTH','合成车型',?,?) returning id",Long.class,brand,actor,actor);
        UUID caseId=UUID.randomUUID(),queryId=UUID.randomUUID();
        db.update("insert into work_order(id,current_responsible_id,created_by,updated_by) values(?,?,?,?)",caseId,actor,actor,actor);
        db.update("insert into map_recommendation_query(id,actor_id,work_order_id,idempotency_key,fingerprint,model_id,direction,strategy,status,expires_at) values(?,?,?,'synthetic-old',?,?,'VEHICLE_TO_SHOP',32,'READY',now()+interval '1 minute')",queryId,actor,caseId,"0".repeat(64),model);
        var after=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration","classpath:db/dev").placeholders(placeholders).load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(4);after.validate();
        assertThat(db.queryForObject("select work_order_version from map_recommendation_query where id=?",Long.class,queryId)).isEqualTo(-1);
        assertThat(db.queryForObject("select source from shop_map_location where shop_id=?",String.class,shop)).isEqualTo("ADMIN_CONFIRMED_GCJ02");
        assertThat(db.queryForObject("select longitude from shop_map_location where shop_id=?",java.math.BigDecimal.class,shop)).isEqualByComparingTo("113.1");
        assertThat(db.queryForObject("select activated from app_user where id=?",Boolean.class,actor)).isTrue();
        assertThat(db.queryForObject("select count(*) from map_call_budget_run",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from map_call_budget_slot",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from map_call_budget_extra_attempt",Long.class)).isZero();
        assertThatThrownBy(()->db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,coordinate_system,source,created_by) values(?,2,?,'合成','GCJ-02','ADMIN_CONFIRMED_GCJ02',?)",shop,district,actor)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        db.update("insert into shop_map_location(shop_id,version,district_id,address_snapshot,coordinate_system,source,created_by,session_id,expires_at) values(?,2,?,'合成测试入口','GCJ-02','AMAP_TEST_SESSION',?,?,now()+interval '10 minutes')",shop,district,actor,UUID.randomUUID());
        assertThat(db.queryForObject("select longitude is null and latitude is null from shop_map_location where shop_id=? and version=2",Boolean.class,shop)).isTrue();
        assertThat(after.migrate().migrationsExecuted).isZero();after.validate();
        assertThat(db.queryForObject("select count(*) from map_call_budget_run",Long.class)).isZero();
        db.update("insert into map_call_budget_run(id) values('opaque-subject-migration-test')");
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values('opaque-subject-migration-test','GEO_NS_A','GEOCODE',?)","a".repeat(64));
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values('opaque-subject-migration-test','REVERSE_NS_A','REVERSE',?)","b".repeat(64));
        db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values('opaque-subject-migration-test','ROUTE_NS_A','DRIVING',?)","c".repeat(64));
        assertThatThrownBy(()->db.update("insert into map_call_budget_slot(run_id,subject,operation) values('opaque-subject-migration-test','GEO_NS_WRONG','DRIVING')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into map_call_budget_slot(run_id,subject,operation) values('opaque-subject-migration-test','unscoped','GEOCODE')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into map_call_budget_slot(run_id,subject,operation,request_hash) values('opaque-subject-migration-test','GEO_NS_DUP','GEOCODE',?)","a".repeat(64))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        db.update("update map_call_budget_slot set attempted_at=current_timestamp where run_id='opaque-subject-migration-test' and subject='GEO_NS_A'");
        assertThatThrownBy(()->db.update("update map_call_budget_slot set attempted_at=null where run_id='opaque-subject-migration-test' and subject='GEO_NS_A'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("select enabled from map_call_budget_run where id='opaque-subject-migration-test'",Boolean.class)).isFalse();
    }
}
