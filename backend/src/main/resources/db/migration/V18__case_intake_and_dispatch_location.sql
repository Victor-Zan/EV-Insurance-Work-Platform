ALTER TABLE work_order ADD COLUMN surveyor_name VARCHAR(100);
ALTER TABLE work_order ADD COLUMN surveyor_phone VARCHAR(32);
ALTER TABLE work_order ADD COLUMN vehicle_color VARCHAR(100);
ALTER TABLE work_order ADD COLUMN dispatch_address_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
-- The former formal-case check required dispatch/insurance data at intake.
-- Keep existing cases valid; new required fields/photos are checked under the case lock by the service.
DO $$ DECLARE constraint_name TEXT; BEGIN
 FOR constraint_name IN SELECT conname FROM pg_constraint
  WHERE conrelid='work_order'::regclass AND contype='c'
   AND pg_get_constraintdef(oid) LIKE '%insurance_company%'
   AND pg_get_constraintdef(oid) LIKE '%accident_at%'
 LOOP EXECUTE format('ALTER TABLE work_order DROP CONSTRAINT %I',constraint_name); END LOOP;
END $$;
ALTER TABLE work_order ADD CONSTRAINT work_order_formal_identity_check CHECK(status='DRAFT' OR (
 NULLIF(btrim(claim_no),'') IS NOT NULL AND NULLIF(btrim(owner_phone),'') IS NOT NULL AND
 NULLIF(btrim(vehicle_brand),'') IS NOT NULL AND NULLIF(btrim(vehicle_model),'') IS NOT NULL AND
 (NULLIF(btrim(vehicle_vin),'') IS NOT NULL OR NULLIF(btrim(vehicle_plate),'') IS NOT NULL OR NULLIF(btrim(vehicle_other_identifier),'') IS NOT NULL)));
ALTER TABLE case_file DROP CONSTRAINT case_file_category_check;
ALTER TABLE case_file ADD CONSTRAINT case_file_category_check CHECK(category IN
 ('NOTICE','SCENE_PHOTO','SHOP_QUOTE','ASSESSMENT','LOSS_ASSESSMENT','ARRIVAL_PHOTO','PROGRESS_PHOTO','COMPLETION_PHOTO'));
ALTER TABLE formal_quote_document_header ALTER COLUMN insurance_company DROP NOT NULL;
ALTER TABLE formal_quote_document_header ALTER COLUMN owner_name DROP NOT NULL;
