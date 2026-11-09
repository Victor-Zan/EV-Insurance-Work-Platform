-- Keep legacy source fields for evidence. New reviews use the sample's first capture time.
ALTER TABLE price_sample_review DROP CONSTRAINT price_sample_review_check;
ALTER TABLE price_sample_review ADD CONSTRAINT price_sample_review_confirmed_check
 CHECK (status <> 'CONFIRMED' OR (part_id IS NOT NULL AND model_id IS NOT NULL
   AND amount IS NOT NULL AND amount > 0 AND unit IS NOT NULL AND btrim(unit) <> ''
   AND price_nature <> 'UNCLASSIFIED'));

CREATE INDEX idx_price_sample_created ON price_sample(created_at DESC,id);
