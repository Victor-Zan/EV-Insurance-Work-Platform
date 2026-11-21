-- First login must distinguish a new identity from a revoked one, including inactive history.
CREATE INDEX wechat_binding_identity_history ON wechat_identity_binding(app_id,openid);
