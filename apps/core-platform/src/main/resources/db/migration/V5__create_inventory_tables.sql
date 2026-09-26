CREATE TABLE inventory (
    product_id UUID PRIMARY KEY REFERENCES products(id),
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity INTEGER NOT NULL CHECK (reserved_quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_reservation_order_product ON reservations(order_id, product_id);
CREATE INDEX idx_reservations_cleanup ON reservations(status, expires_at);
