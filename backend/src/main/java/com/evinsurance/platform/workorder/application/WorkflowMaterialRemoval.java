package com.evinsurance.platform.workorder.application;

import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.integration.storage.ObjectStorageService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class WorkflowMaterialRemoval {
    public record Material(UUID id,String name,String category) {}

    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;
    private final ObjectStorageService storage;

    public WorkflowMaterialRemoval(JdbcTemplate db,ObjectStorageService storage) {
        this.db=db;
        this.named=new NamedParameterJdbcTemplate(db);
        this.storage=storage;
    }

    public List<Material> preview(UUID id,Collection<String> categories) {
        if(categories.isEmpty())return List.of();
        return named.query("""
            SELECT id,original_name,category FROM case_file
            WHERE work_order_id=:caseId AND category IN (:categories)
            ORDER BY category,created_at,id
            """,Map.of("caseId",id,"categories",categories),
            (rs,i)->new Material(rs.getObject("id",UUID.class),rs.getString("original_name"),rs.getString("category")));
    }

    /** The caller holds the work-order lock and the deletion queue commits with the change. */
    public void remove(UUID caseId,List<Material> materials) {
        if(materials.isEmpty())return;
        var files=Map.of("ids",materials.stream().map(Material::id).toList(),"caseId",caseId);
        var jobs=named.query("""
            SELECT id,state FROM ocr_job WHERE file_id IN (:ids) ORDER BY id FOR UPDATE
            """,files,(rs,i)->Map.entry(rs.getObject("id",UUID.class),rs.getString("state")));
        if(jobs.stream().anyMatch(job->"RUNNING".equals(job.getValue())))
            throw ApiException.conflict("MATERIAL_OCR_RUNNING","相关材料正在识别，请等待识别结束后重试");
        db.execute("SET LOCAL app.workflow_material_removal='on'");
        named.update("""
            INSERT INTO workflow_object_removal(object_key,work_order_id)
            SELECT object_key,work_order_id FROM case_file WHERE id IN (:ids) AND work_order_id=:caseId
            ON CONFLICT DO NOTHING
            """,files);
        if(!jobs.isEmpty()) {
            var jobIds=Map.of("ids",jobs.stream().map(Map.Entry::getKey).toList(),"caseId",caseId);
            named.update("""
                INSERT INTO workflow_object_removal(object_key,work_order_id)
                SELECT object_key,:caseId FROM ocr_raw_result WHERE job_id IN (:ids) ON CONFLICT DO NOTHING
                """,jobIds);
            for(String table:List.of("workflow_recognition_link","quote_raw_ocr_line","shop_quote_ocr_command",
                "shop_quote_ocr_rerun","shop_quote_ocr_scope","ocr_confirmation","ocr_review","ocr_raw_result"))
                named.update("DELETE FROM "+table+" WHERE job_id IN (:ids)",jobIds);
            named.update("DELETE FROM ocr_job WHERE id IN (:ids)",jobIds);
        }
        for(String table:List.of("quote_raw_file","case_file_command"))
            named.update("DELETE FROM "+table+" WHERE file_id IN (:ids)",files);
        if(named.update("DELETE FROM case_file WHERE id IN (:ids) AND work_order_id=:caseId",files)!=materials.size())
            throw ApiException.conflict("MATERIAL_REMOVAL_INCOMPLETE","材料记录未完整删除，已取消本次操作");
        db.execute("SET LOCAL app.workflow_material_removal='off'");
    }

    public boolean pending(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM workflow_object_removal WHERE work_order_id=?)",Boolean.class,id));
    }

    @Scheduled(fixedDelayString="${WORKFLOW_REMOVAL_POLL_MS:3000}")
    public void removeQueuedObjects() {
        var keys=db.queryForList("SELECT object_key FROM workflow_object_removal ORDER BY created_at,object_key LIMIT 20",String.class);
        for(String key:keys) {
            try { storage.delete(key); }
            catch(ApiException unavailable) { return; }
            db.update("DELETE FROM workflow_object_removal WHERE object_key=?",key);
        }
    }
}
