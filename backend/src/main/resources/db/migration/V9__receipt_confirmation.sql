CREATE TABLE repair_receipt (
 id BIGSERIAL PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id),
 completion_id UUID NOT NULL REFERENCES repair_completion(id),
 actor_id BIGINT NOT NULL REFERENCES app_user(id),
 actor_role VARCHAR(32) NOT NULL CHECK(actor_role IN ('OWNER','CUSTOMER_SERVICE')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX repair_receipt_case ON repair_receipt(work_order_id,id DESC);
CREATE TRIGGER protect_repair_receipt BEFORE UPDATE OR DELETE ON repair_receipt FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
