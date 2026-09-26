-- Existing nonterminal intents must be drained before extraction (see cutover runbook).
ALTER TABLE payment_attempts ADD COLUMN saga_phase VARCHAR(16) NOT NULL DEFAULT 'DONE'
    CHECK (saga_phase IN ('RESERVING','PAYING','CONFIRMING','RELEASING','DONE'));
ALTER TABLE payment_attempts ADD COLUMN saga_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_attempts ADD COLUMN saga_next_attempt TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE payment_attempts ADD COLUMN saga_error VARCHAR(200);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM payment_attempts WHERE status IN ('PENDING','UNKNOWN')) THEN
        RAISE EXCEPTION 'Drain pending/unknown payments with phase 10 before inventory cutover';
    END IF;
END $$;
CREATE INDEX idx_payment_saga_due ON payment_attempts(saga_next_attempt) WHERE saga_phase <> 'DONE';
