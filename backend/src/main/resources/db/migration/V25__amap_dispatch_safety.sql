-- Incremental to main V23 (activation) + V24 (map). Do not replay the withdrawn
-- local-only V23__amap_dispatch_integration.sql; its schema/checkpoint is retained.
ALTER TABLE work_order ADD COLUMN parking_region_id BIGINT REFERENCES region(id);
ALTER TABLE work_order ADD COLUMN parking_address VARCHAR(255);

-- Never fabricate the case version used by an old query. -1 means unknown and
-- is rejected by current-version checks; all new inserts supply the real version.
ALTER TABLE map_recommendation_query ADD COLUMN work_order_version BIGINT NOT NULL DEFAULT -1 CHECK(work_order_version >= -1);
ALTER TABLE map_recommendation_query ALTER COLUMN work_order_version DROP DEFAULT;

-- Preserve all old rows and their GCJ-02/source invariants. Provider-derived
-- test positions retain only expiring session metadata; coordinates stay in memory.
ALTER TABLE shop_map_location ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE shop_map_location ALTER COLUMN latitude DROP NOT NULL;
ALTER TABLE shop_map_location DROP CONSTRAINT shop_map_location_source_check;
ALTER TABLE shop_map_location ADD CONSTRAINT shop_map_location_source_check
 CHECK(source IN ('ADMIN_CONFIRMED_GCJ02','SYNTHETIC_TEST','AMAP_TEST_SESSION'));
ALTER TABLE shop_map_location ADD COLUMN session_id UUID;
ALTER TABLE shop_map_location ADD COLUMN expires_at TIMESTAMPTZ;
ALTER TABLE shop_map_location ADD CONSTRAINT shop_map_location_provenance_check CHECK (
 (source='AMAP_TEST_SESSION' AND longitude IS NULL AND latitude IS NULL AND session_id IS NOT NULL AND expires_at IS NOT NULL AND expires_at>created_at)
 OR (source<>'AMAP_TEST_SESSION' AND longitude IS NOT NULL AND latitude IS NOT NULL AND session_id IS NULL AND expires_at IS NULL)
);

-- No budget rows are seeded: new databases, restarts and date changes never
-- grant fresh calls. A prior run's attempts must be reconciled before any enable.
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
