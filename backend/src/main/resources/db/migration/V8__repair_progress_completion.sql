-- Only the confirmed repair/progress/completion scope. Receipt/rating/complaints remain pending.
CREATE TABLE repair_context (
 work_order_id UUID PRIMARY KEY REFERENCES work_order(id), version INTEGER NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE TABLE repair_progress (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id), assignment_version INTEGER NOT NULL,
 note VARCHAR(2000) NOT NULL CHECK(length(trim(note))>0), photo_snapshot TEXT NOT NULL,
 created_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX repair_progress_case ON repair_progress(work_order_id,created_at DESC,id DESC);
CREATE TABLE repair_completion (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL UNIQUE REFERENCES work_order(id),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id), assignment_version INTEGER NOT NULL,
 photo_snapshot TEXT NOT NULL, created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE repair_completion_file (
 completion_id UUID NOT NULL REFERENCES repair_completion(id), file_id UUID NOT NULL REFERENCES case_file(id),
 version_no INTEGER NOT NULL CHECK(version_no>0), PRIMARY KEY(completion_id,file_id)
);
CREATE INDEX repair_completion_file_lookup ON repair_completion_file(file_id);
CREATE TABLE repair_command (
 actor_id BIGINT NOT NULL REFERENCES app_user(id), operation VARCHAR(32) NOT NULL, command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, response_json TEXT NOT NULL, PRIMARY KEY(actor_id,operation,command_key)
);
CREATE FUNCTION phase7_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Repair progress, completion evidence and commands are append-only'; END $$;
CREATE TRIGGER protect_repair_progress BEFORE UPDATE OR DELETE ON repair_progress FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_repair_completion BEFORE UPDATE OR DELETE ON repair_completion FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_repair_completion_file BEFORE UPDATE OR DELETE ON repair_completion_file FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_repair_command BEFORE UPDATE OR DELETE ON repair_command FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE FUNCTION protect_completed_photo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM repair_completion_file WHERE file_id=OLD.id) THEN
  RAISE EXCEPTION 'Submitted completion evidence is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_completed_photo BEFORE UPDATE OR DELETE ON case_file FOR EACH ROW EXECUTE FUNCTION protect_completed_photo();
