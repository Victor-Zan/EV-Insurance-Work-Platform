-- Feature-owned additive history only. No changes to existing migration files,
-- no suppliers, cases, accounts, service regions or allowances are seeded.
CREATE TABLE case_workshop_binding (
 shop_id BIGINT PRIMARY KEY REFERENCES repair_shop(id),
 work_order_id UUID NOT NULL REFERENCES work_order(id),
 accident_region_id BIGINT NOT NULL REFERENCES region(id),
 source_kind VARCHAR(40) NOT NULL CHECK(source_kind IN ('MANUAL_VERIFIED','PUBLIC_POI_VERIFIED')),
 identity_hash CHAR(64) NOT NULL CHECK(identity_hash ~ '^[0-9a-f]{64}$'),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(work_order_id,identity_hash)
);
CREATE INDEX case_workshop_case ON case_workshop_binding(work_order_id,shop_id);
CREATE TABLE public_workshop_operation (
 id UUID PRIMARY KEY,
 work_order_id UUID NOT NULL REFERENCES work_order(id),
 actor_id BIGINT NOT NULL REFERENCES app_user(id),
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('SEARCH','ROUTES','MANUAL','INTAKE')),
 idempotency_key VARCHAR(120) NOT NULL,
 fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 status VARCHAR(16) NOT NULL CHECK(status IN ('RUNNING','READY','FAILED')),
 result_shop_id BIGINT REFERENCES repair_shop(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(actor_id,kind,idempotency_key)
);
CREATE INDEX public_workshop_operation_case ON public_workshop_operation(work_order_id,created_at,id);
CREATE FUNCTION protect_case_workshop_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Case workshop scope is immutable; disable the shop instead'; END $$;
CREATE TRIGGER case_workshop_binding_guard BEFORE UPDATE OR DELETE ON case_workshop_binding FOR EACH ROW EXECUTE FUNCTION protect_case_workshop_binding();
CREATE FUNCTION enforce_case_workshop_assignment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE bound_case UUID;
BEGIN
 IF NEW.shop_id IS NULL THEN RETURN NEW; END IF;
 EXECUTE format('SELECT work_order_id FROM %I.case_workshop_binding WHERE shop_id=$1',TG_TABLE_SCHEMA) INTO bound_case USING NEW.shop_id;
 IF bound_case IS NOT NULL AND bound_case<>NEW.work_order_id THEN RAISE EXCEPTION 'Temporary workshop belongs to a different case'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER case_workshop_assignment_guard BEFORE INSERT OR UPDATE OF shop_id,work_order_id ON work_order_assignment FOR EACH ROW EXECUTE FUNCTION enforce_case_workshop_assignment();
CREATE FUNCTION enforce_case_workshop_order() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE bound_case UUID; bound_region BIGINT;
BEGIN
 IF NEW.shop_id IS NULL THEN RETURN NEW; END IF;
 EXECUTE format('SELECT work_order_id,accident_region_id FROM %I.case_workshop_binding WHERE shop_id=$1',TG_TABLE_SCHEMA) INTO bound_case,bound_region USING NEW.shop_id;
 IF bound_case IS NOT NULL AND (bound_case<>NEW.id OR bound_region IS DISTINCT FROM NEW.accident_region_id) THEN RAISE EXCEPTION 'Temporary workshop belongs to a different case or verified service district'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER case_workshop_order_guard BEFORE UPDATE OF shop_id,accident_region_id ON work_order FOR EACH ROW EXECUTE FUNCTION enforce_case_workshop_order();
