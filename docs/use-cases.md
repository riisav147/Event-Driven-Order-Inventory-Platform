# Use Cases

## Customer

1. Register with a normalized unique email.
2. Log in.
3. Browse enabled products.
4. Create or retrieve the active cart.
5. Add, update, and remove cart items.
6. Checkout an active cart into one `CREATED` order.
7. View an owned order and order history.
8. Cancel an order when its state allows cancellation.

## Admin

1. Create a product.
2. Update product data.
3. Disable a product.
4. Inspect permitted operational data.

## Internal business flow

1. Reserve inventory for a created order.
2. Attempt payment through a replaceable mock gateway.
3. Confirm payment and order, or record payment failure and release inventory.
4. Publish an order-confirmed notification event in a later messaging phase.
