CREATE TABLE historical_case_import (
    id UUID PRIMARY KEY,
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    file_name VARCHAR(120) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    total_rows INTEGER NOT NULL CHECK (total_rows BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE historical_case (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES historical_case_import(id),
    source_row INTEGER NOT NULL,
    row_hash CHAR(64) NOT NULL UNIQUE,
    claim_no VARCHAR(128),
    owner_name VARCHAR(120) NOT NULL,
    owner_phone VARCHAR(20) NOT NULL,
    vehicle_plate VARCHAR(100),
    brand VARCHAR(120) NOT NULL,
    model VARCHAR(120) NOT NULL,
    accident_address VARCHAR(255) NOT NULL,
    fault_description VARCHAR(2000) NOT NULL,
    latitude NUMERIC(9,6) NOT NULL,
    longitude NUMERIC(9,6) NOT NULL,
    assessment_amount NUMERIC(38,2),
    repair_date DATE,
    shop_name VARCHAR(120),
    legacy_status VARCHAR(80),
    fault_type VARCHAR(120),
    source_name VARCHAR(120),
    note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(import_id,source_row)
);
CREATE INDEX historical_case_claim ON historical_case(claim_no) WHERE claim_no IS NOT NULL;
CREATE INDEX historical_case_phone ON historical_case(owner_phone);
CREATE INDEX historical_case_created ON historical_case(created_at DESC,id DESC);
CREATE TRIGGER protect_historical_case BEFORE UPDATE OR DELETE ON historical_case
FOR EACH ROW EXECUTE FUNCTION phase7_append_only();

CREATE TABLE historical_case_import_row (
    import_id UUID NOT NULL REFERENCES historical_case_import(id),
    row_number INTEGER NOT NULL,
    raw_json TEXT NOT NULL,
    row_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('READY','INVALID','DUPLICATE','IMPORTED','FAILED')),
    error_code VARCHAR(80),
    warning_code VARCHAR(80),
    archive_id UUID REFERENCES historical_case(id),
    PRIMARY KEY(import_id,row_number)
);
CREATE INDEX historical_case_import_row_status ON historical_case_import_row(import_id,status,row_number);
