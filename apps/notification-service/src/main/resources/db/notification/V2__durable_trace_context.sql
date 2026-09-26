ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(128);
ALTER TABLE outbox_events ADD COLUMN tracestate VARCHAR(512);
