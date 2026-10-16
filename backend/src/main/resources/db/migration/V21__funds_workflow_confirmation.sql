ALTER TABLE funds_context ADD COLUMN staged_flow BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE funds_confirmation (
    id BIGSERIAL PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_order(id),
    direction VARCHAR(16) NOT NULL CHECK(direction IN ('RECEIVE','PAY')),
    basis_hash VARCHAR(64) NOT NULL,
    receipt_id BIGINT NOT NULL REFERENCES repair_receipt(id),
    target_id BIGINT NOT NULL REFERENCES funds_target(id),
    entry_ids UUID[] NOT NULL,
    funds_version INTEGER NOT NULL,
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX funds_confirmation_case_direction ON funds_confirmation(work_order_id,direction,id DESC);
CREATE TRIGGER funds_confirmation_immutable BEFORE UPDATE OR DELETE ON funds_confirmation
    FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
