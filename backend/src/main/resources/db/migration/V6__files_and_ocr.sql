-- Keep the original case UUID even if its stage-four draft is physically deleted.
-- Authorization always resolves the live work_order before accessing this lineage.
CREATE TABLE case_file (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL, group_id UUID NOT NULL,
 category VARCHAR(32) NOT NULL CHECK(category IN ('NOTICE','SHOP_QUOTE','ASSESSMENT','LOSS_ASSESSMENT','ARRIVAL_PHOTO','PROGRESS_PHOTO','COMPLETION_PHOTO')),
 version_no INTEGER NOT NULL CHECK(version_no>0), state VARCHAR(16) NOT NULL CHECK(state IN ('ACTIVE','SUPERSEDED','VOID')),
 object_key VARCHAR(255) NOT NULL UNIQUE, original_name VARCHAR(160) NOT NULL,
 content_type VARCHAR(64) NOT NULL, byte_size BIGINT NOT NULL CHECK(byte_size>0 AND byte_size<=10485760),
 sha256 CHAR(64) NOT NULL, shop_id BIGINT, assignment_version INTEGER,
 uploaded_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(group_id,version_no)
);
CREATE UNIQUE INDEX case_file_active_group ON case_file(group_id) WHERE state='ACTIVE';
CREATE INDEX case_file_order ON case_file(work_order_id,category,created_at DESC,id);
CREATE TABLE case_file_command (
 actor_id BIGINT NOT NULL REFERENCES app_user(id), command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, file_id UUID NOT NULL REFERENCES case_file(id),
 PRIMARY KEY(actor_id,command_key)
);
CREATE TABLE case_material_exception (
 work_order_id UUID NOT NULL, reason VARCHAR(1000) NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES app_user(id), version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(work_order_id,version)
);
CREATE TABLE ocr_job (
 id UUID PRIMARY KEY, file_id UUID NOT NULL UNIQUE REFERENCES case_file(id),
 provider VARCHAR(32) NOT NULL DEFAULT 'MOCK_V1', state VARCHAR(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0), simulate_failure BOOLEAN NOT NULL DEFAULT false,
 lease_token UUID, lease_until TIMESTAMPTZ, error_code VARCHAR(64), candidate_json TEXT,
 review_version INTEGER NOT NULL DEFAULT 0, created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ocr_job_queue ON ocr_job(state,lease_until,created_at);
CREATE TABLE ocr_review (
 job_id UUID NOT NULL REFERENCES ocr_job(id), revision INTEGER NOT NULL,
 candidate_json TEXT NOT NULL, actor_id BIGINT NOT NULL REFERENCES app_user(id),
 command_key VARCHAR(128) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(job_id,revision), UNIQUE(job_id,command_key)
);
CREATE TABLE ocr_confirmation (
 job_id UUID NOT NULL, revision INTEGER NOT NULL, actor_id BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(job_id,revision),
 FOREIGN KEY(job_id,revision) REFERENCES ocr_review(job_id,revision)
);
CREATE FUNCTION phase5_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Historical phase5 records are append-only'; END $$;
CREATE TRIGGER protect_ocr_review BEFORE UPDATE OR DELETE ON ocr_review FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TRIGGER protect_ocr_confirmation BEFORE UPDATE OR DELETE ON ocr_confirmation FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE FUNCTION protect_case_file() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Files cannot be physically deleted'; END IF;
 IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR OLD.state<>'ACTIVE' OR NEW.state NOT IN ('SUPERSEDED','VOID') THEN
  RAISE EXCEPTION 'Only an active file can be superseded or voided';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_case_file_history BEFORE UPDATE OR DELETE ON case_file FOR EACH ROW EXECUTE FUNCTION protect_case_file();
CREATE TRIGGER protect_material_exception BEFORE UPDATE OR DELETE ON case_material_exception FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
