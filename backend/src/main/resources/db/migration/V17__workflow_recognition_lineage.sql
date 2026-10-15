CREATE TABLE workflow_recognition_link (
 business_id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id),
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('QUOTE','ASSESSMENT')),
 job_id UUID NOT NULL, revision INTEGER NOT NULL,
 file_id UUID NOT NULL REFERENCES case_file(id), actor_id BIGINT NOT NULL REFERENCES app_user(id),
 provider VARCHAR(64) NOT NULL, recognition_state VARCHAR(16) NOT NULL, error_code VARCHAR(80), program_json TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(job_id,revision) REFERENCES ocr_confirmation(job_id,revision)
);
CREATE INDEX workflow_recognition_case ON workflow_recognition_link(work_order_id,kind,created_at DESC,business_id);
CREATE TRIGGER protect_workflow_recognition BEFORE UPDATE OR DELETE ON workflow_recognition_link FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TABLE workflow_recognition_command (
 work_order_id UUID NOT NULL REFERENCES work_order(id), actor_id BIGINT NOT NULL REFERENCES app_user(id),
 kind VARCHAR(16) NOT NULL, command_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
 result_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(work_order_id,actor_id,kind,command_key)
);
CREATE TRIGGER protect_workflow_recognition_command BEFORE UPDATE OR DELETE ON workflow_recognition_command FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TABLE text_draft_source (
 id UUID PRIMARY KEY, actor_id BIGINT NOT NULL REFERENCES app_user(id), command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, original_text TEXT NOT NULL CHECK(length(original_text) BETWEEN 1 AND 50000),
 candidate_json TEXT NOT NULL, adopted_json TEXT NOT NULL, work_order_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(actor_id,command_key)
);
CREATE TRIGGER protect_text_draft_source BEFORE UPDATE OR DELETE ON text_draft_source FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE INDEX text_draft_case ON text_draft_source(work_order_id,created_at DESC,id);
