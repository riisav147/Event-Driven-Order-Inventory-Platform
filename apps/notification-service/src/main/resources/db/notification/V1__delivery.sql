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
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    sequence BIGSERIAL UNIQUE NOT NULL,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    version INTEGER NOT NULL,
    topic VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(available_at, sequence) WHERE status <> 'PUBLISHED';
CREATE INDEX idx_outbox_aggregate_pending ON outbox_events(aggregate_id, sequence) WHERE status <> 'PUBLISHED';

CREATE TABLE processed_events (
    consumer_id VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_id, event_id)
);
-- Retain old receipts (including duplicates), but protect subsequent replays.
INSERT INTO processed_events(consumer_id, event_id, processed_at)
SELECT 'order-notifications', event_id, MIN(recorded_at) FROM notification_receipts GROUP BY event_id;

CREATE TABLE dead_letters (
    id UUID PRIMARY KEY,
    dlt_topic VARCHAR(200) NOT NULL,
    dlt_partition INTEGER NOT NULL,
    dlt_offset BIGINT NOT NULL,
    message_key TEXT,
    payload TEXT,
    failure_type TEXT,
    failure_message TEXT,
    original_topic TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replay_outbox_id UUID REFERENCES outbox_events(id),
    replay_requested_at TIMESTAMPTZ,
    replay_requested_by VARCHAR(200),
    replay_reason VARCHAR(500),
    UNIQUE(dlt_topic, dlt_partition, dlt_offset)
);
CREATE INDEX idx_dead_letters_unreplayed ON dead_letters(recorded_at) WHERE replay_outbox_id IS NULL;
ALTER TABLE dead_letters ADD COLUMN original_partition INTEGER;
ALTER TABLE dead_letters ADD COLUMN original_offset BIGINT;
