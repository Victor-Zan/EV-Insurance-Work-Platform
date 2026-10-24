-- Only the explicitly configured, independent development POI schema.
-- This migration grants no calls and changes no existing map/business tables.
CREATE TABLE place_search_run (
 id VARCHAR(80) PRIMARY KEY,
 authorization_ref VARCHAR(120) NOT NULL,
 call_limit INTEGER NOT NULL CHECK(call_limit BETWEEN 1 AND 100),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 closed_at TIMESTAMPTZ
);
CREATE TABLE place_search_slot (
 id BIGSERIAL PRIMARY KEY,
 run_id VARCHAR(80) NOT NULL REFERENCES place_search_run(id),
 ordinal INTEGER NOT NULL CHECK(ordinal>0),
 request_hash CHAR(64) CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 attempted_at TIMESTAMPTZ,
 UNIQUE(run_id,ordinal),
 CHECK((request_hash IS NULL)=(attempted_at IS NULL))
);
CREATE INDEX place_search_available ON place_search_slot(run_id,ordinal) WHERE attempted_at IS NULL;
CREATE FUNCTION protect_place_search_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.closed_at IS NOT NULL OR
 ROW(NEW.id,NEW.authorization_ref,NEW.call_limit,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.authorization_ref,OLD.call_limit,OLD.created_at)
 THEN RAISE EXCEPTION 'Place allowance cannot be deleted, expanded or reopened'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER place_search_run_guard BEFORE UPDATE OR DELETE ON place_search_run FOR EACH ROW EXECUTE FUNCTION protect_place_search_run();
CREATE FUNCTION protect_place_search_slot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE cap INTEGER; active BOOLEAN;
BEGIN
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT call_limit,enabled AND closed_at IS NULL FROM %I.place_search_run WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA)
   INTO cap,active USING NEW.run_id;
  IF cap IS NULL OR NEW.ordinal>cap OR NOT active THEN RAISE EXCEPTION 'Place slot exceeds active authorization'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR OLD.attempted_at IS NOT NULL OR
 ROW(NEW.id,NEW.run_id,NEW.ordinal) IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.ordinal)
 THEN RAISE EXCEPTION 'Place attempts cannot be reset, deleted or reassigned'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER place_search_slot_guard BEFORE INSERT OR UPDATE OR DELETE ON place_search_slot FOR EACH ROW EXECUTE FUNCTION protect_place_search_slot();
