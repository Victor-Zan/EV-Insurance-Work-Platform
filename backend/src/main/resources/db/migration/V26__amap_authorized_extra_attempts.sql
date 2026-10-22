-- No calls are granted by migration. Explicit, separately authorized additions
-- reference the existing run/subject/fingerprint; original attempts never change.
-- The authoritative ledger can be in a different trusted configured schema, so
-- existence and identity are checked by a join under its run lock before HTTP.
CREATE TABLE map_call_budget_extra_attempt (
 id BIGSERIAL PRIMARY KEY,
 ledger_schema VARCHAR(63) NOT NULL CHECK(ledger_schema ~ '^[a-z][a-z0-9_]{0,62}$'),
 run_id VARCHAR(80) NOT NULL,
 subject VARCHAR(32) NOT NULL CHECK(subject IN ('GEO_SUSTECH','GEO_CUHK')),
 operation VARCHAR(16) NOT NULL CHECK(operation='GEOCODE'),
 authorization_ref VARCHAR(96) NOT NULL CHECK(authorization_ref ~ '^[A-Za-z0-9_-]{1,96}$'),
 request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 authorized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 attempted_at TIMESTAMPTZ,
 UNIQUE(ledger_schema,run_id,subject,authorization_ref)
);
CREATE INDEX idx_map_extra_unattempted ON map_call_budget_extra_attempt(ledger_schema,run_id,operation,request_hash,id) WHERE attempted_at IS NULL;
CREATE FUNCTION protect_map_extra_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Extra map authorizations cannot be deleted'; END IF;
 IF OLD.attempted_at IS NOT NULL OR NEW.attempted_at IS NULL OR
  ROW(NEW.id,NEW.ledger_schema,NEW.run_id,NEW.subject,NEW.operation,NEW.authorization_ref,NEW.request_hash,NEW.authorized_at)
  IS DISTINCT FROM
  ROW(OLD.id,OLD.ledger_schema,OLD.run_id,OLD.subject,OLD.operation,OLD.authorization_ref,OLD.request_hash,OLD.authorized_at)
 THEN RAISE EXCEPTION 'Extra map authorizations and attempts are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER map_extra_attempt_immutable BEFORE UPDATE OR DELETE ON map_call_budget_extra_attempt
 FOR EACH ROW EXECUTE FUNCTION protect_map_extra_attempt();
