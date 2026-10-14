-- OCR intake is evidence, not a formal case. Keep original private objects and append-only lineage.
CREATE TABLE ocr_intake_file (
 id UUID PRIMARY KEY, actor_id BIGINT NOT NULL REFERENCES app_user(id),
 command_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
 object_key VARCHAR(255) NOT NULL UNIQUE, original_name VARCHAR(160) NOT NULL,
 content_type VARCHAR(64) NOT NULL CHECK(content_type IN ('image/jpeg','image/png')),
 byte_size BIGINT NOT NULL CHECK(byte_size>0 AND byte_size<=10485760), sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(actor_id,command_key)
);
ALTER TABLE ocr_job ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE ocr_job ADD COLUMN intake_id UUID UNIQUE REFERENCES ocr_intake_file(id);
ALTER TABLE ocr_job ADD COLUMN provider_api VARCHAR(64) NOT NULL DEFAULT 'local-mock';
ALTER TABLE ocr_job ADD COLUMN provider_version VARCHAR(32) NOT NULL DEFAULT '1';
ALTER TABLE ocr_job ADD CONSTRAINT ocr_source_xor CHECK((file_id IS NULL) <> (intake_id IS NULL));
CREATE TABLE ocr_raw_result (
 job_id UUID NOT NULL REFERENCES ocr_job(id), attempt INTEGER NOT NULL CHECK(attempt>0),
 object_key VARCHAR(255) NOT NULL UNIQUE, sha256 CHAR(64) NOT NULL, byte_size INTEGER NOT NULL CHECK(byte_size>0 AND byte_size<=2097152),
 log_id VARCHAR(80), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(job_id,attempt)
);
CREATE TABLE ocr_draft_link (
 job_id UUID PRIMARY KEY, revision INTEGER NOT NULL, work_order_id UUID NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES app_user(id), request_hash CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), FOREIGN KEY(job_id,revision) REFERENCES ocr_confirmation(job_id,revision)
);
-- work_order_id intentionally has no FK: existing draft deletion must not erase original evidence/history.
CREATE TRIGGER protect_ocr_intake BEFORE UPDATE OR DELETE ON ocr_intake_file FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TRIGGER protect_ocr_raw BEFORE UPDATE OR DELETE ON ocr_raw_result FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TRIGGER protect_ocr_draft BEFORE UPDATE OR DELETE ON ocr_draft_link FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE INDEX ocr_intake_actor_time ON ocr_intake_file(actor_id,created_at DESC,id);
CREATE INDEX ocr_provider_queue ON ocr_job(provider,state,created_at,id);
CREATE FUNCTION protect_ocr_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (NEW.id,NEW.file_id,NEW.intake_id,NEW.provider,NEW.provider_api,NEW.provider_version,NEW.created_by,NEW.created_at,NEW.simulate_failure)
  IS DISTINCT FROM (OLD.id,OLD.file_id,OLD.intake_id,OLD.provider,OLD.provider_api,OLD.provider_version,OLD.created_by,OLD.created_at,OLD.simulate_failure) THEN
  RAISE EXCEPTION 'OCR source and provider provenance are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_ocr_origin BEFORE UPDATE OR DELETE ON ocr_job FOR EACH ROW EXECUTE FUNCTION protect_ocr_origin();
