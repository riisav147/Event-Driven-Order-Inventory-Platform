-- Phase 8 audit of simulated notifications, not a processed-event deduplication table.
CREATE TABLE notification_receipts (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    order_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    order_number VARCHAR(32) NOT NULL,
    kafka_topic VARCHAR(200) NOT NULL,
    kafka_partition INTEGER NOT NULL,
    kafka_offset BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notification_order ON notification_receipts(order_id, recorded_at);
CREATE INDEX idx_notification_event ON notification_receipts(event_id);
