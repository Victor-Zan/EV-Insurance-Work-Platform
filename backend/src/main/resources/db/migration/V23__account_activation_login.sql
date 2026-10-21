ALTER TABLE app_user ADD COLUMN activated BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE owner_profile ADD COLUMN invitation_managed BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE account_invitation (
 id UUID PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id),
 token_hash CHAR(64) NOT NULL UNIQUE, purpose VARCHAR(32) NOT NULL CHECK(purpose='ACTIVATE_ACCOUNT'),
 auth_version BIGINT NOT NULL, created_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 used_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ,
 CHECK(expires_at>created_at), CHECK(used_at IS NULL OR revoked_at IS NULL)
);
CREATE UNIQUE INDEX account_invitation_current ON account_invitation(user_id) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX account_invitation_user_time ON account_invitation(user_id,created_at DESC,id);
CREATE TABLE login_handoff (
 token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id),
 auth_version BIGINT NOT NULL, portal VARCHAR(16) NOT NULL CHECK(portal IN ('ADMIN','H5')),
 expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE account_rate_limit (
 bucket_hash CHAR(64) PRIMARY KEY, window_started TIMESTAMPTZ NOT NULL, requests INTEGER NOT NULL CHECK(requests>0)
);
CREATE INDEX account_rate_limit_time ON account_rate_limit(window_started);
CREATE TABLE owner_activation_evidence (
 id BIGSERIAL PRIMARY KEY, work_order_id UUID NOT NULL REFERENCES work_order(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
 actor_id BIGINT NOT NULL REFERENCES app_user(id), reason VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER protect_owner_activation_evidence BEFORE UPDATE OR DELETE ON owner_activation_evidence FOR EACH ROW EXECUTE FUNCTION phase5_append_only();
