CREATE TABLE quote_raw (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), version_no INTEGER NOT NULL CHECK(version_no>0),
 shop_id BIGINT NOT NULL REFERENCES repair_shop(id), assignment_version INTEGER NOT NULL,
 currency VARCHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'), total NUMERIC(38,2) NOT NULL CHECK(total>=0),
 created_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(work_order_id,version_no)
);
CREATE TABLE quote_raw_item (
 raw_quote_id UUID NOT NULL REFERENCES quote_raw(id), line_no INTEGER NOT NULL CHECK(line_no>0), description VARCHAR(255) NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity>0), unit_price NUMERIC(38,2) NOT NULL CHECK(unit_price>=0),
 amount NUMERIC(38,2) NOT NULL CHECK(amount>=0 AND amount=unit_price*quantity), source_snapshot TEXT NOT NULL,
 PRIMARY KEY(raw_quote_id,line_no)
);
CREATE TABLE quote_formal (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), version_no INTEGER NOT NULL CHECK(version_no>0),
 raw_quote_id UUID NOT NULL REFERENCES quote_raw(id), currency VARCHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
 markup_mode VARCHAR(16) NOT NULL CHECK(markup_mode IN ('FIXED_AMOUNT','PERCENTAGE')), markup_value NUMERIC(38,2) NOT NULL CHECK(markup_value>=0),
 original_total NUMERIC(38,2) NOT NULL CHECK(original_total>=0), markup_amount NUMERIC(38,2) NOT NULL CHECK(markup_amount>=0),
 total NUMERIC(38,2) NOT NULL CHECK(total=original_total+markup_amount), calculation_version VARCHAR(64) NOT NULL,
 created_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(work_order_id,version_no),
 CHECK(markup_mode<>'PERCENTAGE' OR markup_value<=100)
);
CREATE TABLE quote_formal_item (
 formal_quote_id UUID NOT NULL REFERENCES quote_formal(id), line_no INTEGER NOT NULL CHECK(line_no>0), description VARCHAR(255) NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity>0), original_unit_price NUMERIC(38,2) NOT NULL CHECK(original_unit_price>=0),
 original_amount NUMERIC(38,2) NOT NULL CHECK(original_amount=quantity*original_unit_price),
 allocated_markup NUMERIC(38,2) NOT NULL CHECK(allocated_markup>=0), external_unit_price NUMERIC(42,6) NOT NULL CHECK(external_unit_price>=0),
 external_amount NUMERIC(38,2) NOT NULL CHECK(external_amount=original_amount+allocated_markup), PRIMARY KEY(formal_quote_id,line_no)
);
CREATE TABLE quote_assessment (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), version_no INTEGER NOT NULL CHECK(version_no>0),
 source_formal_id UUID REFERENCES quote_formal(id), amount NUMERIC(38,2) NOT NULL CHECK(amount>=0), file_snapshot TEXT NOT NULL,
 created_by BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(work_order_id,version_no)
);
CREATE TABLE quotation_context (
 work_order_id UUID PRIMARY KEY REFERENCES work_order(id), version INTEGER NOT NULL DEFAULT 0, basis_version INTEGER NOT NULL DEFAULT 0,
 raw_quote_id UUID REFERENCES quote_raw(id), formal_quote_id UUID REFERENCES quote_formal(id), assessment_id UUID REFERENCES quote_assessment(id),
 insurer_confirmed BOOLEAN NOT NULL DEFAULT false, service_confirmed BOOLEAN NOT NULL DEFAULT false, authorized BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE quotation_confirmation (
 id UUID PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), basis_version INTEGER NOT NULL,
 kind VARCHAR(32) NOT NULL CHECK(kind IN ('INSURER','CUSTOMER_SERVICE')), raw_quote_id UUID REFERENCES quote_raw(id),
 formal_quote_id UUID REFERENCES quote_formal(id), assessment_id UUID REFERENCES quote_assessment(id), file_snapshot TEXT NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(work_order_id,basis_version,kind)
);
CREATE TABLE quotation_event (
 id BIGSERIAL PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), basis_version INTEGER NOT NULL,
 kind VARCHAR(64) NOT NULL, summary VARCHAR(1000) NOT NULL, actor_id BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX quote_raw_case ON quote_raw(work_order_id,version_no DESC);
CREATE INDEX quote_formal_case ON quote_formal(work_order_id,version_no DESC);
CREATE INDEX quote_assessment_case ON quote_assessment(work_order_id,version_no DESC);
CREATE INDEX quote_event_case ON quotation_event(work_order_id,id DESC);
CREATE TABLE quotation_command (
 actor_id BIGINT NOT NULL REFERENCES app_user(id), operation VARCHAR(32) NOT NULL, command_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, response_json TEXT NOT NULL, PRIMARY KEY(actor_id,operation,command_key)
);
CREATE FUNCTION phase6_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Quotation snapshots, confirmations and authorization events are append-only'; END $$;
CREATE TRIGGER protect_quote_raw BEFORE UPDATE OR DELETE ON quote_raw FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_raw_item BEFORE UPDATE OR DELETE ON quote_raw_item FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_formal BEFORE UPDATE OR DELETE ON quote_formal FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_formal_item BEFORE UPDATE OR DELETE ON quote_formal_item FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_assessment BEFORE UPDATE OR DELETE ON quote_assessment FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_confirmation BEFORE UPDATE OR DELETE ON quotation_confirmation FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_event BEFORE UPDATE OR DELETE ON quotation_event FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
CREATE TRIGGER protect_quote_command BEFORE UPDATE OR DELETE ON quotation_command FOR EACH ROW EXECUTE FUNCTION phase6_append_only();
