# Core API Contract Draft

This is a design contract for Phase 2. Controllers are intentionally not implemented in Phase 1.

| Method | Path | Authority | Result |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Create a customer |
| POST | `/api/auth/login` | Public | Authenticate a customer |
| GET | `/api/products` | Public | List enabled products |
| GET | `/api/products/{id}` | Public | Read one product |
| POST | `/api/admin/products` | ADMIN | Create a product |
| PATCH | `/api/admin/products/{id}` | ADMIN | Update a product |
| GET | `/api/cart` | CUSTOMER | Read the active cart |
| POST | `/api/cart/items` | CUSTOMER | Add a product quantity |
| PATCH | `/api/cart/items/{id}` | CUSTOMER | Change a cart quantity |
| DELETE | `/api/cart/items/{id}` | CUSTOMER | Remove a cart item |
| POST | `/api/orders` | CUSTOMER | Create one `CREATED` order from the active cart |
| GET | `/api/orders/{orderNumber}` | Owner or ADMIN | Read an order |
| GET | `/api/orders` | Owner or ADMIN | Read paginated order history |
| POST | `/api/orders/{id}/cancel` | Owner or ADMIN policy | Cancel when the state allows it |

Phase 2 will use request/response DTOs and will map validation errors, not-found errors, conflicts, invariant violations, and unexpected failures to consistent HTTP responses. Status fields will not be accepted as an unrestricted order mutation input.
