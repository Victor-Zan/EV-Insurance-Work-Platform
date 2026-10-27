-- Retain original files, jobs, reviews, confirmations and command history across provider changes.
ALTER TABLE ocr_job DROP CONSTRAINT ocr_job_file_id_key;
ALTER TABLE ocr_job ADD CONSTRAINT ocr_job_file_provider_key UNIQUE(file_id,provider);
ALTER TABLE ocr_job ADD CONSTRAINT ocr_job_id_provider_key UNIQUE(id,provider);

ALTER TABLE shop_quote_ocr_scope ADD COLUMN provider VARCHAR(32);
-- These append-only rows receive only their existing job's immutable source identity.
ALTER TABLE shop_quote_ocr_scope DISABLE TRIGGER protect_shop_quote_ocr_scope;
UPDATE shop_quote_ocr_scope s SET provider=j.provider FROM ocr_job j WHERE j.id=s.job_id;
ALTER TABLE shop_quote_ocr_scope ENABLE TRIGGER protect_shop_quote_ocr_scope;
ALTER TABLE shop_quote_ocr_scope ALTER COLUMN provider SET NOT NULL;
ALTER TABLE shop_quote_ocr_scope DROP CONSTRAINT shop_quote_ocr_scope_work_order_id_shop_id_assignment_versi_key;
ALTER TABLE shop_quote_ocr_scope ADD CONSTRAINT shop_quote_ocr_source_provider_key
 UNIQUE(work_order_id,shop_id,assignment_version,category,file_sha256,provider);
ALTER TABLE shop_quote_ocr_scope ADD CONSTRAINT shop_quote_ocr_job_provider_key UNIQUE(job_id,provider);
ALTER TABLE shop_quote_ocr_scope ADD CONSTRAINT shop_quote_ocr_job_provider_fk
 FOREIGN KEY(job_id,provider) REFERENCES ocr_job(id,provider);

ALTER TABLE shop_quote_ocr_command ADD COLUMN provider VARCHAR(32);
ALTER TABLE shop_quote_ocr_command DISABLE TRIGGER protect_shop_quote_ocr_command;
UPDATE shop_quote_ocr_command c SET provider=s.provider FROM shop_quote_ocr_scope s WHERE s.job_id=c.job_id;
ALTER TABLE shop_quote_ocr_command ENABLE TRIGGER protect_shop_quote_ocr_command;
ALTER TABLE shop_quote_ocr_command ALTER COLUMN provider SET NOT NULL;
ALTER TABLE shop_quote_ocr_command DROP CONSTRAINT shop_quote_ocr_command_pkey;
ALTER TABLE shop_quote_ocr_command ADD PRIMARY KEY(actor_id,command_key,provider);
ALTER TABLE shop_quote_ocr_command ADD CONSTRAINT shop_quote_ocr_command_provider_fk
 FOREIGN KEY(job_id,provider) REFERENCES shop_quote_ocr_scope(job_id,provider);
