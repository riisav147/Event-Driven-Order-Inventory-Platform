# Security Access Matrix

| Endpoint | Public | CUSTOMER | ADMIN |
|---|---:|---:|---:|
| `POST /api/auth/register` | Yes | Yes | Yes |
| `POST /api/auth/login` | Yes | Yes | Yes |
| `GET /api/products` | Yes | Yes | Yes |
| `POST /api/admin/products` | No | No | Yes |
| `/api/cart/**` | No | Own resources | No |
| `/api/orders/**` | No | Own resources | No |

The current Phase 2 `X-Customer-Id` header remains for request shape compatibility, but it is not trusted: it must match the authenticated JWT subject.
