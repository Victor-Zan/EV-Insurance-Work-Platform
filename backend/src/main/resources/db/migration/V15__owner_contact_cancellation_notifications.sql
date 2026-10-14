ALTER TABLE work_order_command ADD COLUMN request_hash VARCHAR(64);
CREATE TABLE owner_contact_event (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    reason VARCHAR(2000) NOT NULL CHECK(length(trim(reason))>0),
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER protect_owner_contact_event BEFORE UPDATE OR DELETE ON owner_contact_event
FOR EACH ROW EXECUTE FUNCTION phase7_append_only();
CREATE INDEX owner_phone_matching ON owner_profile(contact_phone,user_id);

CREATE TABLE work_order_cancellation (
    work_order_id UUID PRIMARY KEY REFERENCES work_order(id),
    owner_id BIGINT REFERENCES app_user(id),
    shop_id BIGINT REFERENCES repair_shop(id),
    assignment_version INTEGER,
    notify_owner BOOLEAN NOT NULL,
    notify_shop BOOLEAN NOT NULL,
    public_reason VARCHAR(2000) NOT NULL,
    cancelled_at TIMESTAMPTZ NOT NULL
);
CREATE TRIGGER protect_work_order_cancellation BEFORE UPDATE OR DELETE ON work_order_cancellation
FOR EACH ROW EXECUTE FUNCTION phase7_append_only();

-- Use the cancellation snapshot, since the assignment is already cancelled when auditing.
-- Deliver atomically with cancellation; no event can be lost when a projector restarts.
CREATE FUNCTION enqueue_cancellation_notification() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cancellation work_order_cancellation%ROWTYPE;
BEGIN
    IF NEW.action<>'WORK_ORDER_CANCEL' OR NEW.object_type<>'WORK_ORDER' THEN RETURN NEW; END IF;
    SELECT * INTO cancellation FROM work_order_cancellation WHERE work_order_id=NEW.object_id::uuid;
    IF NOT FOUND THEN RETURN NEW; END IF;
    INSERT INTO notification_event(audit_id,work_order_id,kind,shop_id,assignment_version,projected_at)
    VALUES(NEW.id,cancellation.work_order_id,'CASE_CANCELLED',cancellation.shop_id,cancellation.assignment_version,now());
    INSERT INTO in_app_notification(event_id,recipient_id,recipient_role)
    SELECT NEW.id,u.id,r.code FROM app_user u
    JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id
    LEFT JOIN shop_account sa ON sa.user_id=u.id
    WHERE u.enabled AND (
        (cancellation.notify_owner AND r.code='OWNER' AND u.id=cancellation.owner_id)
        OR (cancellation.notify_shop AND r.code='REPAIR_SHOP' AND sa.shop_id=cancellation.shop_id)
    ) ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER enqueue_cancellation_notification AFTER INSERT ON audit_log
FOR EACH ROW EXECUTE FUNCTION enqueue_cancellation_notification();
