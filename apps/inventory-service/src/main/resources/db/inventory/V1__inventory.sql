CREATE TABLE inventory (
    product_id UUID PRIMARY KEY,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0)
);
CREATE TABLE reservation_orders (
    order_id UUID PRIMARY KEY,
    request TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED','REJECTED','CONFIRMED','RELEASED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE reservations (
    order_id UUID NOT NULL REFERENCES reservation_orders(order_id),
    product_id UUID NOT NULL REFERENCES inventory(product_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, product_id)
);
