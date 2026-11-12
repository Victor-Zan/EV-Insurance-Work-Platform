-- Derive completion facts from append-only evidence, bounded by the completion event.
CREATE INDEX analytics_completed_history ON work_order_status_history(work_order_id,occurred_at DESC,id DESC) WHERE to_status='COMPLETED';
CREATE INDEX analytics_confirmation_time ON quotation_confirmation(work_order_id,created_at DESC,basis_version DESC) WHERE kind='CUSTOMER_SERVICE';
CREATE VIEW funds_analytics_fact AS
WITH evidence AS (
 SELECT w.id,w.business_no,w.created_by,h.id completion_id,h.occurred_at completed_at,
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
