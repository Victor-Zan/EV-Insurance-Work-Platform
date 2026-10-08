CREATE TABLE work_order_config (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    possible_duplicate_days INTEGER NOT NULL CHECK (possible_duplicate_days BETWEEN 1 AND 365),
    updated_by BIGINT REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO work_order_config(id,possible_duplicate_days) VALUES (1,7);

CREATE TABLE work_order_number_counter (
    business_date DATE PRIMARY KEY,
    last_value INTEGER NOT NULL CHECK (last_value BETWEEN 1 AND 999999)
);

CREATE TABLE work_order (
    id UUID PRIMARY KEY,
    business_no VARCHAR(32) UNIQUE,
    insurance_company VARCHAR(120),
    claim_no VARCHAR(100),
    claim_reported_at TIMESTAMPTZ,
    data_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    current_responsible_id BIGINT NOT NULL REFERENCES app_user(id),
    owner_user_id BIGINT REFERENCES app_user(id),
    owner_binding_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (owner_binding_status IN ('PENDING','BOUND')),
    owner_name VARCHAR(100),
    owner_phone VARCHAR(32),
    policy_no VARCHAR(100),
    vehicle_brand VARCHAR(100),
    vehicle_model VARCHAR(100),
    vehicle_vin VARCHAR(100),
    vehicle_plate VARCHAR(100),
    vehicle_other_identifier VARCHAR(100),
    accident_at TIMESTAMPTZ,
    accident_region_id BIGINT REFERENCES region(id),
    accident_address VARCHAR(255),
    accident_description VARCHAR(2000),
    shop_id BIGINT REFERENCES repair_shop(id),
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT','PENDING_DISPATCH','PENDING_ACCEPTANCE','PENDING_ARRIVAL','ARRIVAL_EXCEPTION','ARRIVED',
        'WAITING_QUOTE','QUOTE_REVIEWING','WAITING_INSURER_ASSESSMENT','WAITING_REPAIR_AUTHORIZATION',
        'REPAIRING','WAITING_OWNER_CONFIRMATION','WAITING_INSURER_PAYMENT','WAITING_SHOP_SETTLEMENT',
        'COMPLETED','CANCELLED','CLOSED')),
    status_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    updated_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((status = 'DRAFT' AND business_no IS NULL) OR (status <> 'DRAFT' AND business_no IS NOT NULL)),
    CHECK ((owner_binding_status = 'BOUND' AND owner_user_id IS NOT NULL)
        OR (owner_binding_status = 'PENDING' AND owner_user_id IS NULL)),
    CHECK (status = 'DRAFT' OR (
        insurance_company IS NOT NULL AND btrim(insurance_company) <> '' AND
        claim_no IS NOT NULL AND btrim(claim_no) <> '' AND
        owner_name IS NOT NULL AND btrim(owner_name) <> '' AND
        owner_phone IS NOT NULL AND btrim(owner_phone) <> '' AND
        vehicle_brand IS NOT NULL AND btrim(vehicle_brand) <> '' AND
        vehicle_model IS NOT NULL AND btrim(vehicle_model) <> '' AND
        accident_at IS NOT NULL AND accident_region_id IS NOT NULL AND
        accident_description IS NOT NULL AND btrim(accident_description) <> '' AND
        (NULLIF(btrim(vehicle_vin),'') IS NOT NULL OR NULLIF(btrim(vehicle_plate),'') IS NOT NULL
            OR NULLIF(btrim(vehicle_other_identifier),'') IS NOT NULL)))
);
CREATE UNIQUE INDEX uk_work_order_formal_claim
    ON work_order (lower(insurance_company),lower(claim_no)) WHERE status <> 'DRAFT';
CREATE INDEX idx_work_order_status_updated ON work_order(status,updated_at DESC,id DESC);
CREATE INDEX idx_work_order_creator_draft ON work_order(created_by,updated_at DESC,id DESC) WHERE status='DRAFT';
CREATE INDEX idx_work_order_shop_status ON work_order(shop_id,status,updated_at DESC,id DESC);
CREATE INDEX idx_work_order_owner_status ON work_order(owner_user_id,status,updated_at DESC,id DESC);
CREATE INDEX idx_work_order_phone_accident ON work_order(owner_phone,accident_at,id) WHERE status <> 'DRAFT';
CREATE INDEX idx_work_order_vin_accident ON work_order(vehicle_vin,accident_at,id) WHERE vehicle_vin IS NOT NULL AND status <> 'DRAFT';
CREATE INDEX idx_work_order_plate_accident ON work_order(vehicle_plate,accident_at,id) WHERE vehicle_plate IS NOT NULL AND status <> 'DRAFT';
CREATE INDEX idx_work_order_policy ON work_order(policy_no,id) WHERE policy_no IS NOT NULL AND status <> 'DRAFT';

CREATE TABLE work_order_assignment (
    id BIGSERIAL PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_order(id),
    assignment_version INTEGER NOT NULL CHECK (assignment_version > 0),
    shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
    reason VARCHAR(500),
    transfer_description VARCHAR(1000),
    assigned_by BIGINT NOT NULL REFERENCES app_user(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_by BIGINT REFERENCES app_user(id),
    responded_at TIMESTAMPTZ,
    cancelled_by BIGINT REFERENCES app_user(id),
    cancelled_at TIMESTAMPTZ,
    UNIQUE(work_order_id,assignment_version)
);
CREATE UNIQUE INDEX uk_work_order_active_assignment ON work_order_assignment(work_order_id)
    WHERE status IN ('PENDING','ACCEPTED');
CREATE INDEX idx_assignment_shop_status ON work_order_assignment(shop_id,status,id DESC);

CREATE TABLE work_order_status_history (
    id BIGSERIAL PRIMARY KEY,
    work_order_id UUID NOT NULL REFERENCES work_order(id),
    from_status VARCHAR(40),
    to_status VARCHAR(40) NOT NULL,
    action VARCHAR(40) NOT NULL,
    reason VARCHAR(1000),
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    actor_roles VARCHAR(150) NOT NULL,
    assignment_version INTEGER,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_work_order_history ON work_order_status_history(work_order_id,occurred_at,id);
CREATE TRIGGER work_order_history_append_only BEFORE UPDATE OR DELETE ON work_order_status_history
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

CREATE TABLE work_order_command (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    work_order_id UUID NOT NULL REFERENCES work_order(id),
    assignment_version INTEGER,
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE(actor_id,operation,idempotency_key)
);
CREATE INDEX idx_work_order_command_target ON work_order_command(work_order_id,assignment_version,operation);
