-- Map qualification is additive: preserve the existing manual dispatch contract.
CREATE TABLE shop_model_capability (
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
 model_id BIGINT NOT NULL REFERENCES price_model(id),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(shop_id,model_id)
);
CREATE INDEX idx_shop_capability_model ON shop_model_capability(model_id,shop_id);

-- Administrator-owned input, NOT an archive of Amap geocoding/route responses.
-- Location versions are append-only; address mismatch invalidates the latest position.
CREATE TABLE shop_map_location (
 id BIGSERIAL PRIMARY KEY,
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
 version INTEGER NOT NULL CHECK(version>0),
 district_id BIGINT NOT NULL REFERENCES region(id),
 address_snapshot VARCHAR(255) NOT NULL CHECK(btrim(address_snapshot)<>''),
 longitude NUMERIC(9,6) CHECK(longitude BETWEEN -180 AND 180),
 latitude NUMERIC(8,6) CHECK(latitude BETWEEN -90 AND 90),
 coordinate_system VARCHAR(16) NOT NULL CHECK(coordinate_system='GCJ-02'),
 source VARCHAR(32) NOT NULL CHECK(source IN ('ADMIN_CONFIRMED_GCJ02','SYNTHETIC_TEST','AMAP_TEST_SESSION')),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 session_id UUID,
 expires_at TIMESTAMPTZ,
 CHECK ((source='AMAP_TEST_SESSION' AND longitude IS NULL AND latitude IS NULL AND session_id IS NOT NULL AND expires_at IS NOT NULL) OR (source<>'AMAP_TEST_SESSION' AND longitude IS NOT NULL AND latitude IS NOT NULL AND session_id IS NULL AND expires_at IS NULL)),
 UNIQUE(shop_id,version)
);
CREATE INDEX idx_shop_map_location_latest ON shop_map_location(shop_id,version DESC);
CREATE TRIGGER shop_map_location_append_only BEFORE UPDATE OR DELETE ON shop_map_location
 FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- Persist only internal provenance/idempotency metadata; no customer address, provider
-- coordinates, road distances, route geometry, credentials or upstream payloads.
CREATE TABLE map_recommendation_query (
 id UUID PRIMARY KEY,
 actor_id BIGINT NOT NULL REFERENCES app_user(id),
 work_order_id UUID NOT NULL REFERENCES work_order(id),
 idempotency_key VARCHAR(120) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 model_id BIGINT NOT NULL REFERENCES price_model(id),
 work_order_version BIGINT NOT NULL,
 direction VARCHAR(32) NOT NULL CHECK(direction='VEHICLE_TO_SHOP'),
 strategy INTEGER NOT NULL CHECK(strategy=32),
 status VARCHAR(20) NOT NULL CHECK(status IN ('RUNNING','READY','FAILED')),
 attempted_calls INTEGER NOT NULL DEFAULT 0 CHECK(attempted_calls BETWEEN 0 AND 50),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(actor_id,idempotency_key)
);
CREATE INDEX idx_map_query_case ON map_recommendation_query(work_order_id,created_at DESC,id);
CREATE TABLE map_recommendation_shop (
 query_id UUID NOT NULL REFERENCES map_recommendation_query(id),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id),
 location_id BIGINT NOT NULL REFERENCES shop_map_location(id),
 PRIMARY KEY(query_id,shop_id)
);

-- Keep parking separate. Existing accident facts and confirmation history are not overwritten.
ALTER TABLE work_order ADD COLUMN parking_region_id BIGINT REFERENCES region(id);
ALTER TABLE work_order ADD COLUMN parking_address VARCHAR(255);

-- One run shared by every process and endpoint. Only hashes of approved requests are retained.
CREATE TABLE map_call_budget_run (
 id VARCHAR(80) PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 closed_at TIMESTAMPTZ
);
CREATE TABLE map_call_budget_slot (
 run_id VARCHAR(80) NOT NULL REFERENCES map_call_budget_run(id),
 subject VARCHAR(32) NOT NULL CHECK(subject IN ('GEO_SUSTECH','GEO_CUHK','GEO_SZU','GEO_SZTU','REVERSE_CHECK','ROUTE_CUHK','ROUTE_SZU','ROUTE_SZTU')),
 operation VARCHAR(16) NOT NULL CHECK(operation IN ('GEOCODE','REVERSE','DRIVING')),
 request_hash CHAR(64),
 attempted_at TIMESTAMPTZ,
 PRIMARY KEY(run_id,subject),
 UNIQUE(run_id,operation,request_hash),
 CHECK ((subject LIKE 'GEO_%' AND operation='GEOCODE') OR (subject LIKE 'ROUTE_%' AND operation='DRIVING') OR (subject='REVERSE_CHECK' AND operation='REVERSE'))
);
CREATE FUNCTION protect_map_budget_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.attempted_at IS NOT NULL THEN
  RAISE EXCEPTION 'Map call attempts cannot be removed, reset or reassigned';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER map_budget_attempt_immutable BEFORE UPDATE OR DELETE ON map_call_budget_slot
 FOR EACH ROW EXECUTE FUNCTION protect_map_budget_attempt();
