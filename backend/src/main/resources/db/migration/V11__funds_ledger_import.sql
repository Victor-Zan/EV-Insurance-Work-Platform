CREATE TABLE funds_context(work_order_id UUID PRIMARY KEY REFERENCES work_order(id),version INTEGER NOT NULL DEFAULT 0);
CREATE TABLE funds_target(
 id BIGSERIAL PRIMARY KEY,work_order_id UUID NOT NULL REFERENCES work_order(id),direction VARCHAR(8) NOT NULL CHECK(direction IN ('RECEIVE','PAY')),
 amount NUMERIC(38,2) CHECK(amount>=0),shop_id BIGINT REFERENCES repair_shop(id),assignment_version INTEGER,
 reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),actor_id BIGINT NOT NULL REFERENCES app_user(id),created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(direction='PAY' OR amount IS NOT NULL),CHECK(direction='RECEIVE' OR (shop_id IS NOT NULL AND assignment_version IS NOT NULL))
);
CREATE INDEX funds_target_case ON funds_target(work_order_id,direction,id DESC);
CREATE TABLE funds_entry(
 id UUID PRIMARY KEY,work_order_id UUID NOT NULL REFERENCES work_order(id),direction VARCHAR(8) NOT NULL CHECK(direction IN ('RECEIVE','PAY')),
 transaction_no VARCHAR(128) NOT NULL,amount NUMERIC(38,2) NOT NULL CHECK(amount>0),occurred_at TIMESTAMPTZ NOT NULL,
 shop_id BIGINT REFERENCES repair_shop(id),assignment_version INTEGER,note VARCHAR(2000),content_hash CHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES app_user(id),created_at TIMESTAMPTZ NOT NULL DEFAULT now(),UNIQUE(direction,transaction_no),
 CHECK(direction='RECEIVE' OR (shop_id IS NOT NULL AND assignment_version IS NOT NULL))
);
CREATE INDEX funds_entry_case ON funds_entry(work_order_id,created_at DESC,id DESC);
CREATE TABLE funds_reversal(id UUID PRIMARY KEY,entry_id UUID NOT NULL UNIQUE REFERENCES funds_entry(id),reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),actor_id BIGINT NOT NULL REFERENCES app_user(id),created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE funds_command(actor_id BIGINT NOT NULL REFERENCES app_user(id),operation VARCHAR(32) NOT NULL,command_key VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,response_json TEXT NOT NULL,PRIMARY KEY(actor_id,operation,command_key));
CREATE TABLE funds_import(
 id UUID PRIMARY KEY,actor_id BIGINT NOT NULL REFERENCES app_user(id),file_name VARCHAR(120) NOT NULL,file_format VARCHAR(8) NOT NULL,
 sha256 CHAR(64) NOT NULL,object_key VARCHAR(512) NOT NULL,total_rows INTEGER NOT NULL CHECK(total_rows BETWEEN 1 AND 2000),created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE funds_import_row(
 import_id UUID NOT NULL REFERENCES funds_import(id),row_number INTEGER NOT NULL,raw_json TEXT NOT NULL,
 case_id UUID REFERENCES work_order(id),status VARCHAR(32) NOT NULL,error_code VARCHAR(128),entry_id UUID REFERENCES funds_entry(id),version INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(import_id,row_number)
);
CREATE TABLE funds_import_resolution(id BIGSERIAL PRIMARY KEY,import_id UUID NOT NULL,row_number INTEGER NOT NULL,case_id UUID NOT NULL REFERENCES work_order(id),reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),actor_id BIGINT NOT NULL REFERENCES app_user(id),created_at TIMESTAMPTZ NOT NULL DEFAULT now(),FOREIGN KEY(import_id,row_number) REFERENCES funds_import_row(import_id,row_number));
CREATE TRIGGER protect_funds_target BEFORE UPDATE OR DELETE ON funds_target FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_funds_entry BEFORE UPDATE OR DELETE ON funds_entry FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_funds_reversal BEFORE UPDATE OR DELETE ON funds_reversal FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_funds_command BEFORE UPDATE OR DELETE ON funds_command FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_funds_import BEFORE UPDATE OR DELETE ON funds_import FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_funds_resolution BEFORE UPDATE OR DELETE ON funds_import_resolution FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE FUNCTION protect_funds_import_raw() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR ROW(NEW.import_id,NEW.row_number,NEW.raw_json) IS DISTINCT FROM ROW(OLD.import_id,OLD.row_number,OLD.raw_json) THEN RAISE EXCEPTION 'Import source is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_funds_import_raw BEFORE UPDATE OR DELETE ON funds_import_row FOR EACH ROW EXECUTE FUNCTION protect_funds_import_raw();
