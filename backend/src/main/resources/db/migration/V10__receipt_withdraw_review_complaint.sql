CREATE TABLE repair_receipt_withdrawal (
 id BIGSERIAL PRIMARY KEY, receipt_id BIGINT NOT NULL UNIQUE REFERENCES repair_receipt(id),
 reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0), actor_id BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE repair_review (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL UNIQUE REFERENCES work_order(id),
 receipt_id BIGINT NOT NULL REFERENCES repair_receipt(id), owner_id BIGINT NOT NULL REFERENCES app_user(id),
 review_text VARCHAR(2000), score INTEGER CHECK(score BETWEEN 1 AND 5),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), CHECK(score IS NOT NULL OR coalesce(length(trim(review_text)),0)>0)
);
CREATE TABLE repair_review_revision (
 id BIGSERIAL PRIMARY KEY, review_id UUID NOT NULL REFERENCES repair_review(id), receipt_id BIGINT NOT NULL REFERENCES repair_receipt(id),
 review_text VARCHAR(2000), score INTEGER CHECK(score BETWEEN 1 AND 5), reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),
 actor_id BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(score IS NOT NULL OR coalesce(length(trim(review_text)),0)>0)
);
CREATE INDEX repair_review_revision_lookup ON repair_review_revision(review_id,id DESC);
CREATE TABLE complaint (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), owner_id BIGINT NOT NULL REFERENCES app_user(id),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id), assignment_version INTEGER NOT NULL,
 description VARCHAR(2000) NOT NULL CHECK(length(trim(description))>0), photo_snapshot TEXT NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED' CHECK(status IN ('SUBMITTED','PROCESSING','RESOLVED','CLOSED')),
 version INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX complaint_case ON complaint(work_order_id,created_at DESC,id DESC);
CREATE TABLE complaint_event (
 id BIGSERIAL PRIMARY KEY, complaint_id UUID NOT NULL REFERENCES complaint(id), kind VARCHAR(32) NOT NULL CHECK(kind IN ('PROCESSING','RESOLVED','CLOSED','CORRECTION')),
 public_note VARCHAR(2000) NOT NULL CHECK(length(trim(public_note))>0), internal_note VARCHAR(2000),
 actor_id BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX complaint_event_lookup ON complaint_event(complaint_id,id DESC);
CREATE TABLE complaint_command (
 actor_id BIGINT NOT NULL REFERENCES app_user(id), operation VARCHAR(32) NOT NULL, command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, response_json TEXT NOT NULL, PRIMARY KEY(actor_id,operation,command_key)
);
CREATE TRIGGER protect_receipt_withdrawal BEFORE UPDATE OR DELETE ON repair_receipt_withdrawal FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_review BEFORE UPDATE OR DELETE ON repair_review FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_review_revision BEFORE UPDATE OR DELETE ON repair_review_revision FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_complaint_event BEFORE UPDATE OR DELETE ON complaint_event FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_complaint_command BEFORE UPDATE OR DELETE ON complaint_command FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE FUNCTION protect_complaint_original() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Complaint original is immutable'; END IF;
 IF ROW(NEW.id,NEW.work_order_id,NEW.owner_id,NEW.shop_id,NEW.assignment_version,NEW.description,NEW.photo_snapshot,NEW.created_at)
  IS DISTINCT FROM ROW(OLD.id,OLD.work_order_id,OLD.owner_id,OLD.shop_id,OLD.assignment_version,OLD.description,OLD.photo_snapshot,OLD.created_at)
 THEN RAISE EXCEPTION 'Complaint original is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_complaint_original BEFORE UPDATE OR DELETE ON complaint FOR EACH ROW EXECUTE FUNCTION protect_complaint_original();
