CREATE TABLE quote_raw_file (
 raw_quote_id UUID NOT NULL REFERENCES quote_raw(id), file_id UUID NOT NULL REFERENCES case_file(id),
 PRIMARY KEY(raw_quote_id,file_id)
);
-- Historical sources are only linked when the submitted OCR lineage proves membership.
INSERT INTO quote_raw_file(raw_quote_id,file_id)
 SELECT r.id,l.file_id FROM quote_raw r JOIN workflow_recognition_link l
 ON l.business_id=r.id AND l.work_order_id=r.work_order_id AND l.kind='QUOTE';
CREATE TRIGGER protect_quote_raw_file BEFORE UPDATE OR DELETE ON quote_raw_file
 FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TABLE quote_formal_review (
 formal_quote_id UUID PRIMARY KEY REFERENCES quote_formal(id),
 adopted_json TEXT NOT NULL, actor_id BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER protect_quote_formal_review BEFORE UPDATE OR DELETE ON quote_formal_review
 FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
