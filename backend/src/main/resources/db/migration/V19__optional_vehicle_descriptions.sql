-- D-049: vehicle brand/model are optional; keep the remaining formal identity constraint.
ALTER TABLE work_order DROP CONSTRAINT work_order_formal_identity_check;
ALTER TABLE work_order ADD CONSTRAINT work_order_formal_identity_check CHECK(status='DRAFT' OR (
 NULLIF(btrim(claim_no),'') IS NOT NULL AND NULLIF(btrim(owner_phone),'') IS NOT NULL AND
 (NULLIF(btrim(vehicle_vin),'') IS NOT NULL OR NULLIF(btrim(vehicle_plate),'') IS NOT NULL OR NULLIF(btrim(vehicle_other_identifier),'') IS NOT NULL)));
ALTER TABLE formal_quote_document_header ALTER COLUMN vehicle_brand DROP NOT NULL;
ALTER TABLE formal_quote_document_header ALTER COLUMN vehicle_model DROP NOT NULL;
