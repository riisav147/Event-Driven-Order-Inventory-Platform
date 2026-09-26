-- Legacy snapshots deliberately remain unmapped: a mutable SKU is not a reliable product identity.
ALTER TABLE order_items ADD COLUMN product_id UUID REFERENCES products(id);
ALTER TABLE orders ADD CONSTRAINT uq_order_payment_amount UNIQUE (id, total, currency);

CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCESS', 'DECLINED', 'ERROR')),
    failure_reason VARCHAR(200),
    gateway_reference VARCHAR(80) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id, amount, currency) REFERENCES orders(id, total, currency)
);

CREATE TABLE order_status_history (
    id BIGSERIAL PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_order_status_history ON order_status_history(order_id, id);
INSERT INTO order_status_history(order_id, status) SELECT id, status FROM orders;

-- Durable stand-in for a provider's independently committed idempotency ledger.
CREATE TABLE mock_gateway_charges (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    amount NUMERIC(12,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'DECLINED', 'ERROR')),
    reference VARCHAR(80) NOT NULL UNIQUE
);
