package com.evinsurance.platform.integration.map;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Reproduces this computer's already-applied, different V25 without repair. */
class LocalLegacyMigrationPostgresIT {
    @TempDir Path migrations;

    @Test void appliedLocalV25UpgradesWithoutRewritingHistoryOrLosingCases() throws Exception {
        var resources=new PathMatchingResourcePatternResolver();
        for(String location:new String[]{"migration","dev"}){
            for(var resource:resources.getResources("classpath:db/"+location+"/V*.sql")){
                String name=resource.getFilename();
                if("V25__amap_dispatch_safety.sql".equals(name))name="V25.1__amap_dispatch_safety.sql";
                try(var source=resource.getInputStream()){Files.copy(source,migrations.resolve(name));}
            }
        }
        try(var source=resources.getResource("classpath:db/local-legacy/V25__allow_repeated_formal_claims.sql").getInputStream()){
            Files.copy(source,migrations.resolve("V25__allow_repeated_formal_claims.sql"));
        }
        String schema="legacy_v25_it_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("TEST_DB_URL"),user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
        if(url==null||user==null||password==null)throw new IllegalStateException("Dedicated PostgreSQL test configuration required");
        String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        var placeholders=Map.of("devAdminHash",hash,"devCustomerServiceHash",hash,"devRepairShopHash",hash,"devOwnerHash",hash);
        var before=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .locations("filesystem:"+migrations.toAbsolutePath()).placeholders(placeholders).target("25").load();
        before.migrate();before.validate();
        var db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));
        int checksum=db.queryForObject("select checksum from flyway_schema_history where version='25'",Integer.class);
        assertThat(checksum).isEqualTo(-1573259309);
        long actor=db.queryForObject("select id from app_user where username='dev_admin'",Long.class);
        UUID caseId=UUID.randomUUID();
        db.update("insert into work_order(id,business_no,status,insurance_company,claim_no,owner_phone,vehicle_vin,accident_address,dispatch_address_confirmed,current_responsible_id,created_by,updated_by) values(?,'SYNTH-LEGACY-1','PENDING_DISPATCH','合成保险','REPEATED','13800000000','SYNTH-VIN-1','合成历史地址',true,?,?,?)",caseId,actor,actor,actor);
        db.update("insert into work_order(id,business_no,status,insurance_company,claim_no,owner_phone,vehicle_vin,current_responsible_id,created_by,updated_by) values(?,'SYNTH-LEGACY-2','PENDING_DISPATCH','合成保险','REPEATED','13800000000','SYNTH-VIN-2',?,?,?)",UUID.randomUUID(),actor,actor,actor);
        var after=Flyway.configure().dataSource(scoped,user,password).schemas(schema).defaultSchema(schema)
            .locations("filesystem:"+migrations.toAbsolutePath()).placeholders(placeholders).load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(6);after.validate();
        assertThat(after.info().current().getVersion().toString()).isEqualTo("30");
        assertThat(db.queryForObject("select count(*) from automatic_dispatch_attempt",Long.class)).isZero();
        assertThat(db.queryForObject("select checksum from flyway_schema_history where version='25'",Integer.class)).isEqualTo(checksum);
        assertThat(db.queryForObject("select script from flyway_schema_history where version='25'",String.class)).isEqualTo("V25__allow_repeated_formal_claims.sql");
        assertThat(db.queryForObject("select count(*) from work_order where claim_no='REPEATED'",Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("select accident_address from work_order where id=?",String.class,caseId)).isEqualTo("合成历史地址");
        assertThat(db.queryForObject("select parking_address from work_order where id=?",String.class,caseId)).isNull();
        assertThat(db.queryForObject("select count(*) from map_call_budget_run",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from map_call_budget_slot",Long.class)).isZero();
        assertThat(after.migrate().migrationsExecuted).isZero();after.validate();
    }
}
