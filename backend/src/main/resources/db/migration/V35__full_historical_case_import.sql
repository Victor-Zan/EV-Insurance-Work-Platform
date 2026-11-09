ALTER TABLE historical_case_import ADD COLUMN format VARCHAR(24) NOT NULL DEFAULT 'LEGACY_17'
 CHECK (format IN ('LEGACY_17','FULL_WORKBOOK'));
ALTER TABLE historical_case ADD COLUMN platform_order_no VARCHAR(128), ADD COLUMN full_record JSONB;
ALTER TABLE historical_case ALTER COLUMN model DROP NOT NULL, ALTER COLUMN accident_address DROP NOT NULL,
 ALTER COLUMN fault_description DROP NOT NULL, ALTER COLUMN latitude DROP NOT NULL, ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE historical_case ADD CONSTRAINT historical_full_record_check
 CHECK (full_record IS NULL OR (platform_order_no IS NOT NULL AND jsonb_typeof(full_record)='object'));

ALTER TABLE price_sample_batch DROP CONSTRAINT price_sample_batch_source_kind_check;
ALTER TABLE price_sample_batch ADD CONSTRAINT price_sample_batch_source_kind_check
 CHECK (source_kind IN ('XIAOBANGBANG','MARKET','SHOP_QUOTE','HISTORICAL_ARCHIVE'));
ALTER TABLE price_sample_review DROP CONSTRAINT price_sample_review_price_nature_check;
ALTER TABLE price_sample_review ADD CONSTRAINT price_sample_review_price_nature_check
 CHECK (price_nature IN ('MARKET_RETAIL','SHOP_QUOTED','SUPPLIER_QUOTED','CP_QUOTED','UNCLASSIFIED'));
