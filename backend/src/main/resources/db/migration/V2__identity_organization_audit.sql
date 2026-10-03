CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES app_user(id),
    updated_by BIGINT REFERENCES app_user(id)
);
CREATE TABLE app_role (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE CHECK (code IN ('ADMIN','CUSTOMER_SERVICE','REPAIR_SHOP','OWNER')),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app_role(code,name) VALUES
('ADMIN','管理员'),('CUSTOMER_SERVICE','客服'),('REPAIR_SHOP','维修网点'),('OWNER','车主');
CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    role_id BIGINT NOT NULL REFERENCES app_role(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id,role_id)
);
CREATE TABLE owner_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
    display_name VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE region (
    id BIGSERIAL PRIMARY KEY,
    parent_id BIGINT,
    level SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 3),
    parent_level SMALLINT GENERATED ALWAYS AS (CASE WHEN parent_id IS NULL THEN NULL ELSE level - 1 END) STORED,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(32) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES app_user(id),
    updated_by BIGINT REFERENCES app_user(id),
    UNIQUE(id,level),
    CHECK ((level=1 AND parent_id IS NULL) OR (level>1 AND parent_id IS NOT NULL)),
    FOREIGN KEY(parent_id,parent_level) REFERENCES region(id,level)
);
CREATE INDEX idx_region_parent_sort ON region(parent_id,sort_order,id);
CREATE TABLE repair_shop (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(32) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    contact_name VARCHAR(100),
    contact_phone VARCHAR(32),
    address VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES app_user(id),
    updated_by BIGINT REFERENCES app_user(id)
);
CREATE TABLE shop_service_region (
    shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
    region_id BIGINT NOT NULL REFERENCES region(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES app_user(id),
    PRIMARY KEY(shop_id,region_id)
);
CREATE INDEX idx_service_region_shop ON shop_service_region(region_id,shop_id);
CREATE TABLE shop_account (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id),
    shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT REFERENCES app_user(id)
);
CREATE INDEX idx_shop_account_shop ON shop_account(shop_id,user_id);
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT REFERENCES app_user(id),
    actor_roles VARCHAR(150) NOT NULL,
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(64),
    summary VARCHAR(8000) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_time ON audit_log(occurred_at DESC,id DESC);
CREATE INDEX idx_audit_object ON audit_log(object_type,object_id,id DESC);
CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append only';
END;
$$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
