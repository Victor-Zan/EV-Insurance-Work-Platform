CREATE INDEX funds_claim_lookup ON work_order(claim_no) WHERE status<>'DRAFT';
CREATE TABLE notification_event(audit_id BIGINT PRIMARY KEY REFERENCES audit_log(id),work_order_id UUID NOT NULL REFERENCES work_order(id),kind VARCHAR(40) NOT NULL,shop_id BIGINT,assignment_version INTEGER,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),projected_at TIMESTAMPTZ);
CREATE INDEX notification_event_pending ON notification_event(audit_id) WHERE projected_at IS NULL;
CREATE TABLE in_app_notification(id BIGSERIAL PRIMARY KEY,event_id BIGINT NOT NULL REFERENCES notification_event(audit_id),recipient_id BIGINT NOT NULL REFERENCES app_user(id),recipient_role VARCHAR(32) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),UNIQUE(event_id,recipient_id,recipient_role));
CREATE INDEX notification_recipient ON in_app_notification(recipient_id,id DESC);
CREATE TABLE notification_read(notification_id BIGINT NOT NULL REFERENCES in_app_notification(id),user_id BIGINT NOT NULL REFERENCES app_user(id),read_at TIMESTAMPTZ NOT NULL DEFAULT now(),PRIMARY KEY(notification_id,user_id));
CREATE TABLE todo_deadline(task_key VARCHAR(512) PRIMARY KEY,work_order_id UUID NOT NULL REFERENCES work_order(id),deadline TIMESTAMPTZ,version INTEGER NOT NULL DEFAULT 0);
CREATE TABLE todo_deadline_event(id BIGSERIAL PRIMARY KEY,task_key VARCHAR(512) NOT NULL REFERENCES todo_deadline(task_key),deadline TIMESTAMPTZ,reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),actor_id BIGINT NOT NULL REFERENCES app_user(id),created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX todo_deadline_history ON todo_deadline_event(task_key,id DESC);
CREATE TRIGGER protect_notification BEFORE UPDATE OR DELETE ON in_app_notification FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_notification_read BEFORE UPDATE OR DELETE ON notification_read FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE TRIGGER protect_deadline_event BEFORE UPDATE OR DELETE ON todo_deadline_event FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE FUNCTION enqueue_in_app_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE case_id UUID;event_kind TEXT;event_shop BIGINT;event_assignment INTEGER;payment_direction TEXT;
BEGIN
 event_kind:=CASE NEW.action WHEN 'WORK_ORDER_DISPATCH' THEN 'DISPATCHED' WHEN 'WORK_ORDER_REASSIGN' THEN 'DISPATCHED' WHEN 'WORK_ORDER_ARRIVE' THEN 'ARRIVED' WHEN 'QUOTE_RAW_SUBMIT' THEN 'QUOTE_REVIEW' WHEN 'QUOTE_ASSESSMENT_RECORD' THEN 'ASSESSMENT_CONFIRM' WHEN 'REPAIR_COMPLETE' THEN 'OWNER_RECEIPT' WHEN 'REPAIR_RECEIPT_CONFIRM' THEN 'OWNER_RECEIVED' WHEN 'REPAIR_RECEIPT_WITHDRAW' THEN 'RECEIPT_WITHDRAWN' WHEN 'COMPLAINT_CREATE' THEN 'COMPLAINT_NEW' WHEN 'COMPLAINT_HANDLE' THEN 'COMPLAINT_UPDATED' WHEN 'FUNDS_TARGET' THEN 'FUNDS_UPDATED' WHEN 'FUNDS_ENTRY' THEN 'FUNDS_UPDATED' WHEN 'FUNDS_REVERSE' THEN 'FUNDS_UPDATED' ELSE NULL END;
 IF event_kind IS NULL THEN RETURN NEW; END IF;
 IF NEW.object_type='WORK_ORDER' THEN case_id:=NEW.object_id::uuid;
 ELSIF NEW.object_type='COMPLAINT' THEN SELECT work_order_id INTO case_id FROM complaint WHERE id=NEW.object_id::uuid;
 ELSIF NEW.object_type='FUNDS_ENTRY' THEN SELECT work_order_id,direction INTO case_id,payment_direction FROM funds_entry WHERE id=NEW.object_id::uuid;
 END IF;
 IF case_id IS NULL THEN RETURN NEW; END IF;
 IF NEW.action='FUNDS_TARGET' THEN SELECT direction INTO payment_direction FROM funds_target WHERE work_order_id=case_id ORDER BY id DESC LIMIT 1; END IF;
 IF event_kind='FUNDS_UPDATED' AND payment_direction='PAY' THEN event_kind:='PAY_UPDATED'; END IF;
 SELECT shop_id,assignment_version INTO event_shop,event_assignment FROM work_order_assignment WHERE work_order_id=case_id AND status IN ('PENDING','ACCEPTED') ORDER BY assignment_version DESC LIMIT 1;
 INSERT INTO notification_event(audit_id,work_order_id,kind,shop_id,assignment_version) VALUES(NEW.id,case_id,event_kind,event_shop,event_assignment);
 RETURN NEW;
END $$;
CREATE TRIGGER enqueue_notification AFTER INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION enqueue_in_app_notification();
