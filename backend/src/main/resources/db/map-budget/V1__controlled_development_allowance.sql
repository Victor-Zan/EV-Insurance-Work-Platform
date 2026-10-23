-- Independent, explicitly selected DEVELOPMENT ledger schema only.
-- No business tables and no allowances are seeded by this migration.
CREATE TABLE development_map_run (
 id VARCHAR(80) PRIMARY KEY,
 authorization_ref VARCHAR(120) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 geocode_limit INTEGER NOT NULL CHECK(geocode_limit BETWEEN 1 AND 100),
 reverse_limit INTEGER NOT NULL CHECK(reverse_limit BETWEEN 1 AND 100),
 driving_limit INTEGER NOT NULL CHECK(driving_limit BETWEEN 1 AND 100),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 closed_at TIMESTAMPTZ
);
CREATE TABLE development_map_slot (
 id BIGSERIAL PRIMARY KEY,
 run_id VARCHAR(80) NOT NULL REFERENCES development_map_run(id),
 operation VARCHAR(16) NOT NULL CHECK(operation IN ('GEOCODE','REVERSE','DRIVING')),
 ordinal INTEGER NOT NULL CHECK(ordinal>0),
 reserved_by UUID,
 reserved_at TIMESTAMPTZ,
 request_hash CHAR(64) CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 attempted_at TIMESTAMPTZ,
 UNIQUE(run_id,operation,ordinal),
 CHECK((reserved_by IS NULL)=(reserved_at IS NULL)),
 CHECK((request_hash IS NULL)=(attempted_at IS NULL))
);
CREATE INDEX development_map_available ON development_map_slot(run_id,operation,id)
 WHERE attempted_at IS NULL AND reserved_by IS NULL;
CREATE FUNCTION protect_development_map_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.closed_at IS NOT NULL OR
 ROW(NEW.id,NEW.authorization_ref,NEW.geocode_limit,NEW.reverse_limit,NEW.driving_limit,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.authorization_ref,OLD.geocode_limit,OLD.reverse_limit,OLD.driving_limit,OLD.created_at)
 THEN RAISE EXCEPTION 'Development authorization cannot be deleted, expanded or reopened'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER development_map_run_guard BEFORE UPDATE OR DELETE ON development_map_run
 FOR EACH ROW EXECUTE FUNCTION protect_development_map_run();
CREATE FUNCTION protect_development_map_slot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE cap INTEGER;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT CASE NEW.operation WHEN 'GEOCODE' THEN geocode_limit WHEN 'REVERSE' THEN reverse_limit ELSE driving_limit END
   INTO cap FROM development_map_run WHERE id=NEW.run_id FOR UPDATE;
  IF cap IS NULL OR NEW.ordinal>cap THEN RAISE EXCEPTION 'Slot exceeds authorization'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR OLD.attempted_at IS NOT NULL OR
 ROW(NEW.id,NEW.run_id,NEW.operation,NEW.ordinal) IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.operation,OLD.ordinal)
 THEN RAISE EXCEPTION 'Development attempts cannot be reset, deleted or reassigned'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER development_map_slot_guard BEFORE INSERT OR UPDATE OR DELETE ON development_map_slot
 FOR EACH ROW EXECUTE FUNCTION protect_development_map_slot();
