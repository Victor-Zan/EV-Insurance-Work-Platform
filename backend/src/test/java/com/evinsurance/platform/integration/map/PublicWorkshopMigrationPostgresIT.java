package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PublicWorkshopMigrationPostgresIT {
    @Test void independentMigrationPreservesBusinessHistoryAndRestrictsTemporaryShopsToTheirCase() {
        String url=System.getenv("TEST_DB_URL"),user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        if(url==null||user==null||password==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        String schema="public_workshop_it_"+UUID.randomUUID().toString().replace("-","");
        String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        var placeholders=Map.of("devAdminHash",hash,"devCustomerServiceHash",hash,"devRepairShopHash",hash,"devOwnerHash",hash);
        var business=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration","classpath:db/dev").placeholders(placeholders).load();
        business.migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));
        long actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        long district=db.queryForObject("select id from region where code='DEV-DISTRICT'",Long.class);
        long city=db.queryForObject("select parent_id from region where id=?",Long.class,district);
        UUID ownCase=UUID.randomUUID(),otherCase=UUID.randomUUID();
        for(UUID id:new UUID[]{ownCase,otherCase})db.update("insert into work_order(id,current_responsible_id,created_by,updated_by,accident_region_id) values(?,?,?,?,?)",id,actor,actor,actor,district);
        long history=db.queryForObject("select count(*) from flyway_schema_history",Long.class);
        long shops=db.queryForObject("select count(*) from repair_shop",Long.class);
        long accounts=db.queryForObject("select count(*) from app_user",Long.class);
        var feature=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .table("flyway_public_workshop_history").baselineOnMigrate(true).baselineVersion("0")
            .locations("classpath:db/public-workshop").load();
        assertThat(feature.migrate().migrationsExecuted).isEqualTo(1);
        feature.validate();
        assertThat(feature.migrate().migrationsExecuted).isZero();
        feature.validate();business.validate();
        assertThat(business.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from flyway_schema_history",Long.class)).isEqualTo(history);
        assertThat(db.queryForObject("select count(*) from repair_shop",Long.class)).isEqualTo(shops);
        assertThat(db.queryForObject("select count(*) from app_user",Long.class)).isEqualTo(accounts);
        assertThat(db.queryForObject("select count(*) from work_order",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from map_call_budget_run",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from map_call_budget_slot",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from case_workshop_binding",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from public_workshop_operation",Long.class)).isZero();

        long shop=db.queryForObject("insert into repair_shop(name,code,created_by,updated_by) values('合成本案测试门店','PUBLIC-MIGRATION-TEST',?,?) returning id",Long.class,actor,actor);
        db.update("insert into case_workshop_binding(shop_id,work_order_id,accident_region_id,source_kind,identity_hash,created_by) values(?,?,?,'MANUAL_VERIFIED',?,?)",shop,ownCase,district,"a".repeat(64),actor);
        assertThatThrownBy(()->db.update("update work_order set shop_id=? where id=?",shop,otherCase)).isInstanceOf(DataAccessException.class);
        db.update("update work_order set shop_id=? where id=?",shop,ownCase);
        long otherDistrict=db.queryForObject("insert into region(parent_id,level,name,code,created_by,updated_by) values(?,3,'合成其他区县','PUBLIC-OTHER-DISTRICT',?,?) returning id",Long.class,city,actor,actor);
        assertThatThrownBy(()->db.update("update work_order set accident_region_id=? where id=?",otherDistrict,ownCase)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by,assigned_at) values(?,1,?,'PENDING',?,now())",otherCase,shop,actor)).isInstanceOf(DataAccessException.class);
        db.update("insert into work_order_assignment(work_order_id,assignment_version,shop_id,status,assigned_by,assigned_at) values(?,1,?,'PENDING',?,now())",ownCase,shop,actor);
        assertThatThrownBy(()->db.update("update case_workshop_binding set work_order_id=? where shop_id=?",otherCase,shop)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->db.update("delete from case_workshop_binding where shop_id=?",shop)).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("select shop_id from work_order where id=?",Long.class,otherCase)).isNull();
        assertThat(db.queryForObject("select accident_region_id from work_order where id=?",Long.class,ownCase)).isEqualTo(district);
        assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,ownCase)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from work_order_assignment where work_order_id=?",Long.class,otherCase)).isZero();
    }
}
