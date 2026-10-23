package com.evinsurance.platform.organization.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderRows;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated case-bound creation, never a general customer-service shop/account API. */
@Service
public class CaseWorkshopRegistry {
    private final JdbcTemplate db;private final AuditService audit;private final TransactionTemplate tx;
    private final boolean enabled;
    public CaseWorkshopRegistry(JdbcTemplate db,PlatformTransactionManager manager,AuditService audit,Environment env){
        this.db=db;this.audit=audit;tx=new TransactionTemplate(manager);enabled=env.getProperty("PUBLIC_WORKSHOP_ENABLED",Boolean.class,false);
        if(enabled){
            String schema=db.queryForObject("SELECT current_schema()",String.class);
            if(schema==null||!schema.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid workshop business schema");
            Flyway.configure().dataSource(Objects.requireNonNull(db.getDataSource())).schemas(schema).defaultSchema(schema).table("flyway_public_workshop_history").baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/public-workshop").load().migrate();
        }
    }
    public boolean enabled(){return enabled;}
    public void requireEnabled(){if(!enabled)throw ApiException.conflict("PUBLIC_WORKSHOP_DISABLED","公开维修门店功能尚未启用");}
    public void assertScope(long shopId,UUID caseId,Long accidentRegion){
        if(!enabled)return;
        var rows=db.queryForList("SELECT work_order_id,accident_region_id FROM case_workshop_binding WHERE shop_id=?",shopId);
        if(!rows.isEmpty()&&(!caseId.equals(rows.getFirst().get("work_order_id"))||accidentRegion==null||accidentRegion.longValue()!=((Number)rows.getFirst().get("accident_region_id")).longValue()))throw ApiException.conflict("CASE_WORKSHOP_SCOPE","临时门店仅可用于登记时核实的本案与事故服务区县，请重新核实");
    }
    public PageResponse<WorkOrderRows.ShopOption> eligible(UUID caseId,long region,int page,int size){
        int offset=PageResponse.offset(page,size);
        String from=" FROM repair_shop s JOIN shop_service_region sr ON sr.shop_id=s.id LEFT JOIN case_workshop_binding b ON b.shop_id=s.id WHERE s.enabled AND sr.region_id=? AND (b.shop_id IS NULL OR b.work_order_id=?)";
        long total=Objects.requireNonNull(db.queryForObject("SELECT count(DISTINCT s.id)"+from,Long.class,region,caseId));
        var rows=db.query("SELECT DISTINCT s.id,s.code,s.name,s.address"+from+" ORDER BY s.name,s.id LIMIT ? OFFSET ?",(rs,n)->{var row=new WorkOrderRows.ShopOption();row.setId(rs.getLong("id"));row.setCode(rs.getString("code"));row.setName(rs.getString("name"));row.setAddress(rs.getString("address"));return row;},region,caseId,size,offset);
        return new PageResponse<>(page,size,total,rows);
    }
    public record Operation(UUID id,String fingerprint,String status,Long resultShopId){}
    public record Begun(Operation operation,boolean created){}
    public Begun begin(UUID caseId,String kind,String key,String fingerprint){
        requireEnabled();var actor=CurrentUser.require();if(!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
        if(key==null||key.isBlank()||key.length()>120)throw ApiException.invalid("Idempotency-Key is required (max 120)");
        return tx.execute(status->{UUID id=UUID.randomUUID();int created=db.update("INSERT INTO public_workshop_operation(id,work_order_id,actor_id,kind,idempotency_key,fingerprint,status,expires_at) VALUES(?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP+interval '10 minutes') ON CONFLICT(actor_id,kind,idempotency_key) DO NOTHING",id,caseId,actor.id(),kind,key,fingerprint);
            var op=db.queryForObject("SELECT id,fingerprint,status,result_shop_id,work_order_id FROM public_workshop_operation WHERE actor_id=? AND kind=? AND idempotency_key=?",(rs,n)->{if(!caseId.equals(rs.getObject("work_order_id",UUID.class)))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","查询键对应其他案件");return new Operation(rs.getObject("id",UUID.class),rs.getString("fingerprint"),rs.getString("status"),rs.getObject("result_shop_id",Long.class));},actor.id(),kind,key);
            if(op==null||!fingerprint.equals(op.fingerprint()))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","同一操作键不能用于不同输入");return new Begun(op,created==1);
        });
    }
    public void finish(UUID id,boolean success){tx.executeWithoutResult(status->db.update("UPDATE public_workshop_operation SET status=? WHERE id=? AND actor_id=? AND status='RUNNING'",success?"READY":"FAILED",id,CurrentUser.require().id()));}
    public record TemporaryShop(long shopId,String name,String address,UUID caseId,String notice){}
    public TemporaryShop create(UUID caseId,long version,long accidentRegion,String name,String address,String source,String identity,String key){
        return tx.execute(status->{
            var begun=begin(caseId,"INTAKE",key,identity);var op=begun.operation();
            if(!begun.created()){if(op.resultShopId()!=null)return view(op.resultShopId(),caseId);throw ApiException.conflict("WORKSHOP_INTAKE_IN_PROGRESS","本次临时网点登记尚未完成，请读取或重试原操作键");}
            var orders=db.queryForList("SELECT version,status,accident_region_id,dispatch_address_confirmed FROM work_order WHERE id=? FOR UPDATE",caseId);
            if(orders.isEmpty())throw ApiException.missing("Work order");var order=orders.getFirst();
            if(((Number)order.get("version")).longValue()!=version||!Set.of("PENDING_DISPATCH","PENDING_ACCEPTANCE","PENDING_ARRIVAL","ARRIVAL_EXCEPTION").contains(order.get("status"))||!Boolean.TRUE.equals(order.get("dispatch_address_confirmed"))||!Objects.equals(order.get("accident_region_id"),accidentRegion))throw ApiException.conflict("WORKSHOP_CASE_CHANGED","案件、事故区县或停车位置已变化，请刷新并重新核实");
            Long district=db.queryForObject("SELECT count(*) FROM region r JOIN region c ON c.id=r.parent_id JOIN region p ON p.id=c.parent_id WHERE r.id=? AND r.level=3 AND c.level=2 AND p.code='440000' AND r.enabled AND c.enabled AND p.enabled",Long.class,accidentRegion);
            if(district==null||district!=1)throw ApiException.invalid("请先核实启用的广东事故服务区县");
            var existing=db.queryForList("SELECT shop_id FROM case_workshop_binding WHERE work_order_id=? AND identity_hash=?",Long.class,caseId,identity);
            long shopId;
            if(!existing.isEmpty())shopId=existing.getFirst();
            else{
                String display="【临时／仅本案】"+name,code="TMP-"+UUID.randomUUID().toString().replace("-","").substring(0,24);long actor=CurrentUser.require().id();
                Long duplicate=db.queryForObject("SELECT count(*) FROM repair_shop WHERE lower(btrim(name)) IN (lower(?),lower(?)) AND lower(btrim(address))=lower(?)",Long.class,name,display,address);
                if(duplicate!=null&&duplicate>0)throw ApiException.conflict("WORKSHOP_IDENTITY_CONFLICT","发现同名同址网点，请通过既有网点选择或联系管理员核实；不会自动合并");
                shopId=Objects.requireNonNull(db.queryForObject("INSERT INTO repair_shop(name,code,enabled,address,created_by,updated_by) VALUES(?,?,true,?,?,?) RETURNING id",Long.class,display,code,address,actor,actor));
                db.update("INSERT INTO case_workshop_binding(shop_id,work_order_id,accident_region_id,source_kind,identity_hash,created_by) VALUES(?,?,?,?,?,?)",shopId,caseId,accidentRegion,source,identity,actor);
                db.update("INSERT INTO shop_service_region(shop_id,region_id,created_by) VALUES(?,?,?)",shopId,accidentRegion,actor);
                audit.record(CurrentUser.require(),AuditService.Action.SHOP_CREATE,"REPAIR_SHOP",shopId,"Case-limited e-bike workshop; caseId="+caseId+"; source="+source+"; account not created");
                audit.record(CurrentUser.require(),AuditService.Action.SERVICE_REGION_CHANGE,"REPAIR_SHOP",shopId,"Case-limited regionId="+accidentRegion+"; caseId="+caseId);
            }
            db.update("UPDATE public_workshop_operation SET status='READY',result_shop_id=? WHERE id=?",shopId,op.id());return view(shopId,caseId);
        });
    }
    private TemporaryShop view(long shopId,UUID caseId){return db.queryForObject("SELECT s.name,s.address FROM repair_shop s JOIN case_workshop_binding b ON b.shop_id=s.id WHERE s.id=? AND b.work_order_id=?",(rs,n)->new TemporaryShop(shopId,rs.getString("name"),rs.getString("address"),caseId,"仅本案临时门店；账号仍需管理员按原邀请流程开通。选择不等于派单，最后使用原派单按钮。"),shopId,caseId);}
}
