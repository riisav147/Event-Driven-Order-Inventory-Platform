# Order State Machine

Order transitions are domain operations. A caller cannot set an arbitrary status or jump from `CREATED` directly to `CONFIRMED`.

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> RESERVED: inventory reserved
    CREATED --> CANCELLED: customer/admin cancellation
    RESERVED --> PAID: payment succeeds
    RESERVED --> PAYMENT_FAILED: payment declines/errors
    PAYMENT_FAILED --> CANCELLED: inventory released
    PAID --> CONFIRMED: order confirmation
    RESERVED --> CANCELLED: cancellation and release
```

The payment-failure path records the failure before releasing the reservation, preserving an auditable outcome. Later asynchronous phases will make the event and compensation boundaries explicit.
