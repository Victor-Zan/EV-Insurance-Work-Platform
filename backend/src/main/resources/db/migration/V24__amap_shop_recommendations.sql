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
 longitude NUMERIC(9,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 latitude NUMERIC(8,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 coordinate_system VARCHAR(16) NOT NULL CHECK(coordinate_system='GCJ-02'),
 source VARCHAR(32) NOT NULL CHECK(source IN ('ADMIN_CONFIRMED_GCJ02','SYNTHETIC_TEST')),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
