-- Independent of the unmerged map V17. Existing files/tasks/provider history are unchanged.
CREATE TABLE shop_quote_ocr_scope (
 job_id UUID PRIMARY KEY REFERENCES ocr_job(id),
 work_order_id UUID NOT NULL REFERENCES work_order(id),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id), assignment_version INTEGER NOT NULL CHECK(assignment_version>0),
 file_sha256 CHAR(64) NOT NULL, category VARCHAR(32) NOT NULL CHECK(category IN ('SHOP_QUOTE','ASSESSMENT')),
 page_no INTEGER NOT NULL CHECK(page_no BETWEEN 1 AND 20),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(work_order_id,shop_id,assignment_version,category,file_sha256)
);
CREATE INDEX shop_quote_ocr_case ON shop_quote_ocr_scope(work_order_id,shop_id,assignment_version,created_at,job_id);
CREATE TABLE shop_quote_ocr_command (
 actor_id BIGINT NOT NULL REFERENCES app_user(id), command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, job_id UUID NOT NULL REFERENCES shop_quote_ocr_scope(job_id),
 PRIMARY KEY(actor_id,command_key)
);
CREATE TRIGGER protect_shop_quote_ocr_command BEFORE UPDATE OR DELETE ON shop_quote_ocr_command FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TABLE quote_raw_ocr_line (
 raw_quote_id UUID NOT NULL REFERENCES quote_raw(id), line_no INTEGER NOT NULL CHECK(line_no>0),
 job_id UUID NOT NULL REFERENCES shop_quote_ocr_scope(job_id), revision INTEGER NOT NULL,
 item_index INTEGER NOT NULL CHECK(item_index BETWEEN 0 AND 199),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(raw_quote_id,line_no),
 UNIQUE(raw_quote_id,job_id,item_index),
 FOREIGN KEY(job_id,revision) REFERENCES ocr_confirmation(job_id,revision)
);
CREATE TRIGGER protect_shop_quote_ocr_scope BEFORE UPDATE OR DELETE ON shop_quote_ocr_scope FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
CREATE TRIGGER protect_quote_raw_ocr_line BEFORE UPDATE OR DELETE ON quote_raw_ocr_line FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
