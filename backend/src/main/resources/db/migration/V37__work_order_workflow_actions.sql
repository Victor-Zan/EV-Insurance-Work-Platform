ALTER TABLE work_order ADD COLUMN responsibility_transferred boolean NOT NULL DEFAULT false;
ALTER TABLE work_order ADD COLUMN deletion_pending boolean NOT NULL DEFAULT false;
ALTER TABLE work_order ADD COLUMN recalled_business_no varchar(32);
CREATE TABLE work_order_deletion_request (
 work_order_id uuid PRIMARY KEY REFERENCES work_order(id),
 requested_by bigint NOT NULL REFERENCES app_user(id),
 reason varchar(1000) NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE work_order_status_history ADD COLUMN completion_responsible_id bigint REFERENCES app_user(id);

CREATE FUNCTION capture_completion_responsibility() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.to_status='COMPLETED' AND NEW.from_status IS DISTINCT FROM 'COMPLETED' THEN
   SELECT current_responsible_id INTO NEW.completion_responsible_id FROM work_order WHERE id=NEW.work_order_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER capture_completion_responsibility BEFORE INSERT ON work_order_status_history
 FOR EACH ROW EXECUTE FUNCTION capture_completion_responsibility();

ALTER TABLE funds_context ADD COLUMN receive_withdrawal bigint NOT NULL DEFAULT 0;
ALTER TABLE funds_context ADD COLUMN pay_withdrawal bigint NOT NULL DEFAULT 0;

CREATE TABLE workflow_object_removal (
 object_key varchar(255) PRIMARY KEY,
 work_order_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX workflow_object_removal_case ON workflow_object_removal(work_order_id);

CREATE FUNCTION notify_work_order_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.action='WORK_ORDER_TRANSFER' AND NEW.object_type='WORK_ORDER' THEN
   INSERT INTO notification_event(audit_id,work_order_id,kind,projected_at)
     VALUES(NEW.id,NEW.object_id::uuid,'WORK_ORDER_TRANSFERRED',now());
   INSERT INTO in_app_notification(event_id,recipient_id,recipient_role)
     SELECT NEW.id,current_responsible_id,'CUSTOMER_SERVICE' FROM work_order WHERE id=NEW.object_id::uuid;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notify_work_order_transfer AFTER INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION notify_work_order_transfer();

-- The workflow transaction may erase the selected material lineage. Normal edits remain append-only.
CREATE OR REPLACE FUNCTION phase5_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND current_setting('app.workflow_material_removal',true)='on'
    AND TG_TABLE_NAME IN ('workflow_recognition_link','ocr_review','ocr_confirmation','ocr_raw_result',
      'shop_quote_ocr_rerun','shop_quote_ocr_scope','shop_quote_ocr_command','quote_raw_ocr_line','workflow_recognition_command')
 THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Historical phase5 records are append-only';
END $$;
CREATE OR REPLACE FUNCTION phase6_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND current_setting('app.workflow_material_removal',true)='on'
    AND TG_TABLE_NAME IN ('quote_raw_file','quotation_command') THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Historical phase6 records are append-only';
END $$;
CREATE OR REPLACE FUNCTION protect_case_file() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   IF current_setting('app.workflow_material_removal',true)='on' THEN RETURN OLD; END IF;
   RAISE EXCEPTION 'Files cannot be physically deleted';
 END IF;
 IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR OLD.state<>'ACTIVE' OR NEW.state NOT IN ('SUPERSEDED','VOID') THEN
   RAISE EXCEPTION 'Only an active file can be superseded or voided';
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION protect_completed_photo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM repair_completion_file WHERE file_id=OLD.id) THEN
  RAISE EXCEPTION 'Submitted completion evidence is immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE VIEW funds_analytics_fact AS
WITH evidence AS (
 SELECT w.id,w.business_no,coalesce(h.completion_responsible_id,w.created_by) AS created_by,h.id completion_id,h.occurred_at completed_at,
   coalesce(a.shop_id,p.shop_id) shop_id,q.id confirmation_id,r.id receive_target_id,p.id pay_target_id,
   q.file_snapshot::jsonb->'financialBasis' financial,
   r.amount old_receive,p.amount old_pay
 FROM work_order w
 JOIN LATERAL (SELECT * FROM work_order_status_history WHERE work_order_id=w.id AND to_status='COMPLETED'
   ORDER BY occurred_at DESC,id DESC LIMIT 1) h ON true
 LEFT JOIN work_order_assignment a ON a.work_order_id=w.id AND a.assignment_version=h.assignment_version
 LEFT JOIN LATERAL (SELECT * FROM quotation_confirmation WHERE work_order_id=w.id AND kind='CUSTOMER_SERVICE'
   AND created_at<=h.occurred_at ORDER BY created_at DESC,basis_version DESC LIMIT 1) q ON true
 LEFT JOIN LATERAL (SELECT * FROM funds_target WHERE work_order_id=w.id AND direction='RECEIVE'
   AND created_at<=h.occurred_at ORDER BY id DESC LIMIT 1) r ON true
 LEFT JOIN LATERAL (SELECT * FROM funds_target WHERE work_order_id=w.id AND direction='PAY'
   AND created_at<=h.occurred_at ORDER BY id DESC LIMIT 1) p ON true
 WHERE w.status='COMPLETED'
), amounts AS (
 SELECT *,CASE WHEN financial IS NOT NULL THEN (financial->>'insuranceAmount')::numeric(38,2) ELSE old_receive END insurance,
   CASE WHEN financial IS NOT NULL THEN (financial->>'shopAmount')::numeric(38,2) ELSE old_pay END payable
 FROM evidence
)
SELECT f.*,insurance-payable profit,
 CASE WHEN insurance IS NULL OR payable IS NULL THEN '缺少完成时的应收或应付依据'
      WHEN shop_id IS NULL THEN '缺少完成时的承修网点' END pending_reason,
 u.display_name creator_name,coalesce(s.name,'未记录网点') shop_name
FROM amounts f JOIN app_user u ON u.id=f.created_by LEFT JOIN repair_shop s ON s.id=f.shop_id;

