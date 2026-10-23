-- Qualify the run lookup using the trigger's own schema, independent of the
-- caller's business search_path. V1 and any already-tested schema stay intact.
CREATE OR REPLACE FUNCTION protect_development_map_slot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE cap INTEGER;
BEGIN
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT CASE $1 WHEN ''GEOCODE'' THEN geocode_limit WHEN ''REVERSE'' THEN reverse_limit ELSE driving_limit END FROM %I.development_map_run WHERE id=$2 FOR UPDATE',TG_TABLE_SCHEMA)
   INTO cap USING NEW.operation,NEW.run_id;
  IF cap IS NULL OR NEW.ordinal>cap THEN RAISE EXCEPTION 'Slot exceeds authorization'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR OLD.attempted_at IS NOT NULL OR
 ROW(NEW.id,NEW.run_id,NEW.operation,NEW.ordinal) IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.operation,OLD.ordinal)
 THEN RAISE EXCEPTION 'Development attempts cannot be reset, deleted or reassigned'; END IF;
 RETURN NEW;
END $$;
