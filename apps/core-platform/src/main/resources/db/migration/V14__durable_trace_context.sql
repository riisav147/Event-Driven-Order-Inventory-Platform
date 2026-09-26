ALTER TABLE payment_attempts ADD COLUMN traceparent VARCHAR(128);
ALTER TABLE payment_attempts ADD COLUMN tracestate VARCHAR(512);
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(128);
ALTER TABLE outbox_events ADD COLUMN tracestate VARCHAR(512);
