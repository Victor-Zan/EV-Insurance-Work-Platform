CREATE TABLE wechat_identity_binding (
    id UUID PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    openid VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    role VARCHAR(32) NOT NULL CHECK (role IN ('OWNER','REPAIR_SHOP')),
    shop_id BIGINT REFERENCES repair_shop(id),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT REFERENCES app_user(id),
    revoke_reason VARCHAR(2000),
    CHECK (length(app_id) > 0 AND length(openid) > 0),
    CHECK ((role='OWNER' AND shop_id IS NULL) OR (role='REPAIR_SHOP' AND shop_id IS NOT NULL)),
    CHECK ((revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL AND version=0)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL AND length(trim(revoke_reason))>0 AND version=1))
);
CREATE UNIQUE INDEX wechat_binding_active_identity ON wechat_identity_binding(app_id,openid) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX wechat_binding_active_user ON wechat_identity_binding(app_id,user_id) WHERE revoked_at IS NULL;
CREATE INDEX wechat_binding_user_history ON wechat_identity_binding(user_id,created_at DESC,id);

CREATE FUNCTION protect_wechat_binding_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Wechat identity history cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF ROW(NEW.id,NEW.app_id,NEW.openid,NEW.user_id,NEW.role,NEW.shop_id,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.app_id,OLD.openid,OLD.user_id,OLD.role,OLD.shop_id,OLD.created_at)
        OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION 'Wechat identity only permits a single recorded revocation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_wechat_binding BEFORE UPDATE OR DELETE ON wechat_identity_binding
    FOR EACH ROW EXECUTE FUNCTION protect_wechat_binding_history();
