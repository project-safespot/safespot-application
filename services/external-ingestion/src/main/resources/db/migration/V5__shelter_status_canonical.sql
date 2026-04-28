-- V5: normalize shelter_status to English canonical values
-- V1 set default '운영중' but the live DB has a check constraint requiring
--   OPERATING | STOPPED | PREPARING
-- This migration:
--   1. changes the column default to the canonical English value
--   2. migrates any rows that were written with the Korean default
--   3. ensures the check constraint is in place (idempotent drop+add handles
--      the case where it was manually applied during runtime testing)

ALTER TABLE shelter ALTER COLUMN shelter_status SET DEFAULT 'OPERATING';

UPDATE shelter SET shelter_status = 'OPERATING' WHERE shelter_status = '운영중';

ALTER TABLE shelter DROP CONSTRAINT IF EXISTS shelter_shelter_status_check;
ALTER TABLE shelter ADD CONSTRAINT shelter_shelter_status_check
    CHECK (shelter_status IN ('OPERATING', 'STOPPED', 'PREPARING'));
