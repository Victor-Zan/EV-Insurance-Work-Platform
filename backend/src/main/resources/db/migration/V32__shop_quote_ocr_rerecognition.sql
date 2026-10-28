-- Explicit manual calls only; immutable commands preserve the per-original limit across reloads/providers.
CREATE TABLE shop_quote_ocr_rerun (
 file_id UUID NOT NULL REFERENCES case_file(id), rerun_no INTEGER NOT NULL CHECK(rerun_no BETWEEN 1 AND 3),
 job_id UUID NOT NULL REFERENCES shop_quote_ocr_scope(job_id),
 expected_version INTEGER NOT NULL CHECK(expected_version>=0), attempt INTEGER NOT NULL CHECK(attempt>0),
 actor_id BIGINT NOT NULL REFERENCES app_user(id), command_key VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(file_id,rerun_no), UNIQUE(job_id,attempt), UNIQUE(actor_id,command_key)
);
CREATE TRIGGER protect_shop_quote_ocr_rerun BEFORE UPDATE OR DELETE ON shop_quote_ocr_rerun
 FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
