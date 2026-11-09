ALTER TABLE price_part ADD COLUMN unit VARCHAR(24);
ALTER TABLE price_part ADD COLUMN oe_code VARCHAR(120);
ALTER TABLE price_part ADD COLUMN manufacturer_code VARCHAR(120);

CREATE TABLE price_sample_batch (
 id UUID PRIMARY KEY,
 source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN ('XIAOBANGBANG','MARKET','SHOP_QUOTE')),
 source_key VARCHAR(200) NOT NULL,
 file_name VARCHAR(120), object_key VARCHAR(500),
 created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(source_kind,source_key)
);
CREATE TABLE price_sample (
 id UUID PRIMARY KEY,
 batch_id UUID NOT NULL REFERENCES price_sample_batch(id),
 row_number INTEGER NOT NULL CHECK (row_number > 0),
 original JSONB NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(batch_id,row_number)
);
CREATE TABLE price_sample_review (
 sample_id UUID NOT NULL REFERENCES price_sample(id),
 revision INTEGER NOT NULL CHECK (revision > 0),
 status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','EXCLUDED')),
 part_id BIGINT REFERENCES price_part(id), model_id BIGINT REFERENCES price_model(id),
 amount NUMERIC(18,2), unit VARCHAR(24),
 part_brand VARCHAR(120) NOT NULL DEFAULT '', quality VARCHAR(120) NOT NULL DEFAULT '',
 region VARCHAR(120) NOT NULL DEFAULT '', price_date DATE,
 price_nature VARCHAR(24) NOT NULL CHECK (price_nature IN ('MARKET_RETAIL','SHOP_QUOTED','UNCLASSIFIED')),
 reason VARCHAR(1000) NOT NULL CHECK (btrim(reason) <> ''),
 created_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(sample_id,revision),
 CHECK(status <> 'CONFIRMED' OR (part_id IS NOT NULL AND model_id IS NOT NULL
   AND amount IS NOT NULL AND amount > 0 AND unit IS NOT NULL AND btrim(unit) <> ''
   AND price_date IS NOT NULL AND price_nature <> 'UNCLASSIFIED'))
);
CREATE INDEX idx_price_sample_batch ON price_sample(batch_id,row_number);
CREATE INDEX idx_price_sample_review_reference ON price_sample_review(model_id,part_id,price_date,sample_id) WHERE status='CONFIRMED';
CREATE INDEX idx_price_sample_batch_created ON price_sample_batch(created_at,id);

CREATE FUNCTION protect_price_sample_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Price sample evidence is immutable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='price_sample' AND NEW.id=OLD.id AND NEW.batch_id=OLD.batch_id
    AND NEW.row_number=OLD.row_number AND NEW.original=OLD.original AND NEW.created_at=OLD.created_at
    AND NEW.revision=OLD.revision+1 THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'Append a price sample review instead of changing evidence' USING ERRCODE='23514';
END $$;
CREATE TRIGGER protect_price_sample BEFORE UPDATE OR DELETE ON price_sample FOR EACH ROW EXECUTE FUNCTION protect_price_sample_evidence();
CREATE TRIGGER protect_price_sample_review BEFORE UPDATE OR DELETE ON price_sample_review FOR EACH ROW EXECUTE FUNCTION protect_price_sample_evidence();
CREATE TRIGGER protect_price_sample_batch BEFORE UPDATE OR DELETE ON price_sample_batch FOR EACH ROW EXECUTE FUNCTION protect_price_sample_evidence();
