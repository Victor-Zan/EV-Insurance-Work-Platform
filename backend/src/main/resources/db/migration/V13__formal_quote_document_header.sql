CREATE TABLE formal_quote_document_header (
    formal_quote_id UUID PRIMARY KEY REFERENCES quote_formal(id),
    business_no VARCHAR(64) NOT NULL,
    claim_no VARCHAR(128),
    insurance_company VARCHAR(200) NOT NULL,
    owner_name VARCHAR(120) NOT NULL,
    vehicle_brand VARCHAR(120) NOT NULL,
    vehicle_model VARCHAR(120) NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL CHECK (snapshot_kind IN ('ISSUE', 'BACKFILL'))
);

-- Existing formal quotes predate document generation. Their header is captured at migration time.
INSERT INTO formal_quote_document_header
    (formal_quote_id,business_no,claim_no,insurance_company,owner_name,vehicle_brand,vehicle_model,snapshot_kind)
SELECT q.id,w.business_no,w.claim_no,w.insurance_company,w.owner_name,w.vehicle_brand,w.vehicle_model,'BACKFILL'
FROM quote_formal q JOIN work_order w ON w.id=q.work_order_id;

CREATE TRIGGER protect_formal_quote_document_header BEFORE UPDATE OR DELETE ON formal_quote_document_header
FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
