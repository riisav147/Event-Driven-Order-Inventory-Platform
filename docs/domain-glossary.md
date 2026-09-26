# Domain Glossary

- Customer: A user who browses the catalog, owns a cart, and places orders.
- Admin: A user allowed to manage catalog data and inspect operational data.
- Product: The current sellable catalog record identified by a unique SKU.
- Cart: A customer-owned, mutable collection of product quantities before checkout.
- Order: The customer’s immutable checkout snapshot and aggregate root for order state.
- Order item: A product snapshot stored on an order, including SKU, name, unit price, and quantity.
- Inventory: The authoritative stock record for a product, including available and reserved quantity.
- Reservation: An explicit hold of inventory for an order. Its lifecycle is ACTIVE, CONFIRMED, RELEASED, or EXPIRED.
- Payment attempt: An auditable attempt to charge an order through the payment adapter.

## Ownership

Identity owns users and roles. Catalog owns current product data. Cart owns active cart contents. Order owns order items and order transitions. Inventory owns stock and reservations. Payment owns payment attempts. Notification owns delivery tracking after asynchronous eventing is introduced.
