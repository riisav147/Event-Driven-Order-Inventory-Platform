-- Historical tables are retained as a rollback/audit snapshot, never used by runtime code.
-- Do not silently abandon manually created Phase 5 reservations during extraction.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM inventory WHERE reserved_quantity <> 0)
       OR EXISTS (SELECT 1 FROM reservations WHERE status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Drain all legacy reservations before inventory extraction; see docs/service-extraction.md';
    END IF;
END $$;
