CREATE INDEX idx_products_enabled_name ON products(enabled, name);
CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
CREATE UNIQUE INDEX uq_users_email_normalized ON users (LOWER(email));
