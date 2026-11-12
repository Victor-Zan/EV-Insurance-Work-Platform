-- Approval is checked under the case lock. Allow deletion only within that transaction.
CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND current_setting('app.approved_work_order_deletion',true)='on' THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Audit records are append only';
END $$;
CREATE OR REPLACE FUNCTION phase7_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND current_setting('app.approved_work_order_deletion',true)='on' THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Historical records are append-only';
END $$;
CREATE OR REPLACE FUNCTION phase5_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND (
   current_setting('app.approved_work_order_deletion',true)='on'
   OR (current_setting('app.workflow_material_removal',true)='on'
    AND TG_TABLE_NAME IN ('workflow_recognition_link','ocr_review','ocr_confirmation','ocr_raw_result',
      'shop_quote_ocr_rerun','shop_quote_ocr_scope','shop_quote_ocr_command','quote_raw_ocr_line','workflow_recognition_command'))
 ) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Historical phase5 records are append-only';
END $$;
CREATE OR REPLACE FUNCTION phase6_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND (current_setting('app.approved_work_order_deletion',true)='on'
    OR (current_setting('app.workflow_material_removal',true)='on' AND TG_TABLE_NAME IN ('quote_raw_file','quotation_command')))
 THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Quotation snapshots, confirmations and authorization events are append-only';
END $$;
CREATE OR REPLACE FUNCTION protect_complaint_original() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   IF current_setting('app.approved_work_order_deletion',true)='on' THEN RETURN OLD; END IF;
   RAISE EXCEPTION 'Complaint original is immutable';
 END IF;
 IF ROW(NEW.id,NEW.work_order_id,NEW.owner_id,NEW.shop_id,NEW.assignment_version,NEW.description,NEW.photo_snapshot,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.id,OLD.work_order_id,OLD.owner_id,OLD.shop_id,OLD.assignment_version,OLD.description,OLD.photo_snapshot,OLD.created_at)
 THEN RAISE EXCEPTION 'Complaint original is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION protect_funds_import_raw() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   IF current_setting('app.approved_work_order_deletion',true)='on' THEN RETURN OLD; END IF;
   RAISE EXCEPTION 'Import source is immutable';
 END IF;
 IF ROW(NEW.import_id,NEW.row_number,NEW.raw_json) IS DISTINCT FROM ROW(OLD.import_id,OLD.row_number,OLD.raw_json)
 THEN RAISE EXCEPTION 'Import source is immutable'; END IF;
 RETURN NEW;
END $$;
ALTER TABLE workflow_object_removal ALTER COLUMN object_key TYPE varchar(512);

CREATE FUNCTION erase_approved_work_order_records(case_uuid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE import_ids uuid[]; intake_jobs uuid[]; intake_files uuid[]; audit_ids bigint[];
BEGIN
 IF current_setting('app.approved_work_order_deletion',true) IS DISTINCT FROM 'on'
    OR NOT EXISTS(SELECT 1 FROM work_order w JOIN work_order_deletion_request r ON r.work_order_id=w.id
                  WHERE w.id=case_uuid AND w.deletion_pending AND w.status<>'COMPLETED')
 THEN RAISE EXCEPTION 'A pending deletion request for an incomplete case is required'; END IF;

 SELECT array_agg(DISTINCT import_id) INTO import_ids FROM (
   SELECT import_id FROM funds_import_row WHERE case_id=case_uuid OR entry_id IN (SELECT id FROM funds_entry WHERE work_order_id=case_uuid)
   UNION SELECT import_id FROM funds_import_resolution WHERE case_id=case_uuid
 ) i;
 IF EXISTS(SELECT 1 FROM funds_import_row WHERE import_id=ANY(import_ids) AND case_id IS DISTINCT FROM case_uuid)
    OR EXISTS(SELECT 1 FROM funds_import_resolution WHERE import_id=ANY(import_ids) AND case_id<>case_uuid)
 THEN RAISE EXCEPTION 'Shared funds import must be separated before erasure'; END IF;
 INSERT INTO workflow_object_removal(object_key,work_order_id)
 SELECT object_key,case_uuid FROM funds_import WHERE id=ANY(import_ids) ON CONFLICT DO NOTHING;

 SELECT array_agg(job_id) INTO intake_jobs FROM ocr_draft_link WHERE work_order_id=case_uuid;
 PERFORM id FROM ocr_job WHERE id=ANY(intake_jobs) ORDER BY id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM ocr_job WHERE id=ANY(intake_jobs) AND state='RUNNING')
 THEN RAISE EXCEPTION 'Wait for intake OCR to finish'; END IF;
 SELECT array_agg(intake_id) INTO intake_files FROM ocr_job WHERE id=ANY(intake_jobs);
 INSERT INTO workflow_object_removal(object_key,work_order_id)
 SELECT object_key,case_uuid FROM ocr_intake_file WHERE id=ANY(intake_files)
 UNION ALL SELECT object_key,case_uuid FROM ocr_raw_result WHERE job_id=ANY(intake_jobs)
 ON CONFLICT DO NOTHING;

 SELECT array_agg(id) INTO audit_ids FROM audit_log a WHERE
   (a.object_type='WORK_ORDER' AND a.object_id=case_uuid::text)
   OR (a.object_type='MAP_SIMULATION_DESTINATION' AND a.object_id=case_uuid::text)
   OR (a.object_type='CASE_FILE' AND a.object_id IN (SELECT id::text FROM case_file WHERE work_order_id=case_uuid))
   OR (a.object_type='OCR_JOB' AND a.object_id IN (
       SELECT id::text FROM ocr_job WHERE id=ANY(intake_jobs) OR file_id IN (SELECT id FROM case_file WHERE work_order_id=case_uuid)))
   OR (a.object_type='TEXT_DRAFT_SOURCE' AND a.object_id IN (SELECT id::text FROM text_draft_source WHERE work_order_id=case_uuid))
   OR (a.object_type='FORMAL_QUOTE' AND a.object_id IN (SELECT id::text FROM quote_formal WHERE work_order_id=case_uuid))
   OR (a.object_type='COMPLAINT' AND a.object_id IN (SELECT id::text FROM complaint WHERE work_order_id=case_uuid))
   OR (a.object_type='FUNDS_ENTRY' AND a.object_id IN (SELECT id::text FROM funds_entry WHERE work_order_id=case_uuid))
   OR (a.object_type='MAP_QUERY' AND a.object_id IN (SELECT id::text FROM map_recommendation_query WHERE work_order_id=case_uuid))
   OR (a.object_type='FUNDS_IMPORT' AND a.object_id IN (SELECT id::text FROM funds_import WHERE id=ANY(import_ids)))
   OR (a.object_type='NOTIFICATION' AND a.object_id IN (SELECT n.id::text FROM in_app_notification n
       JOIN notification_event e ON e.audit_id=n.event_id WHERE e.work_order_id=case_uuid))
   OR (a.object_type IN ('PUBLIC_WORKSHOP','PUBLIC_WORKSHOP_MANUAL','PUBLIC_WORKSHOP_ROUTE')
       AND position('caseId='||case_uuid::text||';' IN a.summary)>0);

 DELETE FROM notification_read WHERE notification_id IN (
   SELECT n.id FROM in_app_notification n JOIN notification_event e ON e.audit_id=n.event_id WHERE e.work_order_id=case_uuid);
 DELETE FROM in_app_notification WHERE event_id IN (SELECT audit_id FROM notification_event WHERE work_order_id=case_uuid);
 DELETE FROM notification_event WHERE work_order_id=case_uuid;
 DELETE FROM audit_log WHERE id=ANY(audit_ids);
 DELETE FROM todo_deadline_event WHERE task_key IN (SELECT task_key FROM todo_deadline WHERE work_order_id=case_uuid);
 DELETE FROM todo_deadline WHERE work_order_id=case_uuid;
 DELETE FROM funds_confirmation WHERE work_order_id=case_uuid;
 DELETE FROM funds_import_resolution WHERE import_id=ANY(import_ids);
 DELETE FROM funds_import_row WHERE import_id=ANY(import_ids);
 DELETE FROM funds_import WHERE id=ANY(import_ids);
 DELETE FROM funds_command WHERE position(case_uuid::text IN response_json)>0
    OR EXISTS(SELECT 1 FROM funds_entry WHERE work_order_id=case_uuid AND position(id::text IN response_json)>0);
 DELETE FROM funds_reversal WHERE entry_id IN (SELECT id FROM funds_entry WHERE work_order_id=case_uuid);
 DELETE FROM funds_entry WHERE work_order_id=case_uuid;
 DELETE FROM funds_target WHERE work_order_id=case_uuid;
 DELETE FROM funds_context WHERE work_order_id=case_uuid;
 DELETE FROM complaint_command WHERE position(case_uuid::text IN response_json)>0
    OR EXISTS(SELECT 1 FROM complaint WHERE work_order_id=case_uuid AND position(id::text IN response_json)>0);
 DELETE FROM complaint_event WHERE complaint_id IN (SELECT id FROM complaint WHERE work_order_id=case_uuid);
 DELETE FROM complaint WHERE work_order_id=case_uuid;
 DELETE FROM repair_review_revision WHERE review_id IN (SELECT id FROM repair_review WHERE work_order_id=case_uuid);
 DELETE FROM repair_review WHERE work_order_id=case_uuid;
 DELETE FROM repair_receipt_withdrawal WHERE receipt_id IN (SELECT id FROM repair_receipt WHERE work_order_id=case_uuid);
 DELETE FROM repair_receipt WHERE work_order_id=case_uuid;
 DELETE FROM repair_completion_file WHERE completion_id IN (SELECT id FROM repair_completion WHERE work_order_id=case_uuid);
 DELETE FROM repair_completion WHERE work_order_id=case_uuid;
 DELETE FROM repair_progress WHERE work_order_id=case_uuid;
 DELETE FROM repair_command WHERE position(case_uuid::text IN response_json)>0;
 DELETE FROM repair_context WHERE work_order_id=case_uuid;
 DELETE FROM quotation_command WHERE position(case_uuid::text IN response_json)>0;
 DELETE FROM workflow_recognition_command WHERE work_order_id=case_uuid;
 DELETE FROM workflow_recognition_link WHERE work_order_id=case_uuid;
 DELETE FROM quotation_confirmation WHERE work_order_id=case_uuid;
 DELETE FROM quotation_event WHERE work_order_id=case_uuid;
 DELETE FROM quotation_context WHERE work_order_id=case_uuid;
 DELETE FROM quote_assessment WHERE work_order_id=case_uuid;
 DELETE FROM quote_formal_review WHERE formal_quote_id IN (SELECT id FROM quote_formal WHERE work_order_id=case_uuid);
 DELETE FROM formal_quote_document_header WHERE formal_quote_id IN (SELECT id FROM quote_formal WHERE work_order_id=case_uuid);
 DELETE FROM quote_formal_item WHERE formal_quote_id IN (SELECT id FROM quote_formal WHERE work_order_id=case_uuid);
 DELETE FROM quote_formal WHERE work_order_id=case_uuid;
 DELETE FROM quote_raw_file WHERE raw_quote_id IN (SELECT id FROM quote_raw WHERE work_order_id=case_uuid);
 DELETE FROM quote_raw_ocr_line WHERE raw_quote_id IN (SELECT id FROM quote_raw WHERE work_order_id=case_uuid);
 DELETE FROM quote_raw_item WHERE raw_quote_id IN (SELECT id FROM quote_raw WHERE work_order_id=case_uuid);
 DELETE FROM quote_raw WHERE work_order_id=case_uuid;
 DELETE FROM ocr_draft_link WHERE work_order_id=case_uuid;
 DELETE FROM ocr_confirmation WHERE job_id=ANY(intake_jobs);
 DELETE FROM ocr_review WHERE job_id=ANY(intake_jobs);
 DELETE FROM ocr_raw_result WHERE job_id=ANY(intake_jobs);
 DELETE FROM ocr_job WHERE id=ANY(intake_jobs);
 DELETE FROM ocr_intake_file WHERE id=ANY(intake_files);
 DELETE FROM text_draft_source WHERE work_order_id=case_uuid;
 DELETE FROM case_material_exception WHERE work_order_id=case_uuid;
 DELETE FROM owner_activation_evidence WHERE work_order_id=case_uuid;
 DELETE FROM automatic_dispatch_attempt WHERE work_order_id=case_uuid;
 DELETE FROM map_recommendation_shop WHERE query_id IN (SELECT id FROM map_recommendation_query WHERE work_order_id=case_uuid);
 DELETE FROM map_recommendation_query WHERE work_order_id=case_uuid;
 DELETE FROM work_order_cancellation WHERE work_order_id=case_uuid;
 DELETE FROM work_order_status_history WHERE work_order_id=case_uuid;
 DELETE FROM work_order_command WHERE work_order_id=case_uuid;
 DELETE FROM work_order_assignment WHERE work_order_id=case_uuid;
END $$;
