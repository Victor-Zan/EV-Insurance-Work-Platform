-- Only request identity and status persist; provider coordinates remain short lived.
CREATE TABLE automatic_dispatch_attempt (
    actor_id BIGINT NOT NULL REFERENCES app_user(id),
    work_order_id UUID NOT NULL REFERENCES work_order(id),
    id UUID NOT NULL UNIQUE,
    fingerprint VARCHAR(64) NOT NULL,
    request_key UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_id, work_order_id)
);
