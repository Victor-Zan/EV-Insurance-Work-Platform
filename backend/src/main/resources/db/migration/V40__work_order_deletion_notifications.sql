CREATE FUNCTION notify_work_order_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE case_id uuid; applicant bigint; requested timestamptz; event_kind text;
BEGIN
 IF NEW.object_type<>'WORK_ORDER' OR NEW.action NOT IN ('WORK_ORDER_DELETE_REQUEST','WORK_ORDER_DELETE_REJECT') THEN
  RETURN NEW;
 END IF;
 case_id:=NEW.object_id::uuid;
 SELECT requested_by,requested_at INTO applicant,requested FROM work_order_deletion_request WHERE work_order_id=case_id;
 IF NOT FOUND THEN RETURN NEW; END IF;
 IF NEW.action='WORK_ORDER_DELETE_REQUEST' THEN
  IF NOT ('CUSTOMER_SERVICE'=ANY(string_to_array(NEW.actor_roles,','))) THEN RETURN NEW; END IF;
  event_kind:='WORK_ORDER_DELETION_REQUESTED';
 ELSE
  event_kind:='WORK_ORDER_DELETION_REJECTED';
 END IF;
 INSERT INTO notification_event(audit_id,work_order_id,kind,created_at,projected_at)
 VALUES(NEW.id,case_id,event_kind,CASE WHEN NEW.action='WORK_ORDER_DELETE_REQUEST' THEN requested ELSE now() END,now());
 INSERT INTO in_app_notification(event_id,recipient_id,recipient_role)
 SELECT NEW.id,u.id,r.code FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN app_role r ON r.id=ur.role_id
 WHERE u.enabled AND ((event_kind='WORK_ORDER_DELETION_REQUESTED' AND r.code='ADMIN')
   OR (event_kind='WORK_ORDER_DELETION_REJECTED' AND u.id=applicant AND r.code='CUSTOMER_SERVICE'));
 RETURN NEW;
END $$;
CREATE TRIGGER notify_work_order_deletion AFTER INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION notify_work_order_deletion();
