ALTER TABLE dead_letters ADD COLUMN original_partition INTEGER;
ALTER TABLE dead_letters ADD COLUMN original_offset BIGINT;
