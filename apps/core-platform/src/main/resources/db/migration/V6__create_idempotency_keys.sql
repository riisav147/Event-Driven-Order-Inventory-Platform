CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_order_id UUID REFERENCES orders(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys(status, expires_at);
