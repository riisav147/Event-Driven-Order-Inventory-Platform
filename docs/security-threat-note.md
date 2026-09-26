# Security Threat Note

## Controls in this phase

- Passwords are stored as BCrypt hashes, never plaintext.
- Access tokens are signed with an HMAC key supplied through `JWT_SECRET`.
- Tokens are short-lived and stateless; the current policy is 15 minutes.
- Invalid, tampered, and expired tokens do not populate the security context.
- Customer cart and order access checks compare the requested owner with the authenticated JWT subject.
- Admin routes require the `ADMIN` role; customer routes require `CUSTOMER`.

## Operational rules

Never commit `JWT_SECRET`, log access tokens, log passwords, or expose sensitive request bodies. CORS should be restricted to the eventual client origin before public deployment. Refresh-token rotation and revocation are deliberately deferred until a concrete use case requires them.
