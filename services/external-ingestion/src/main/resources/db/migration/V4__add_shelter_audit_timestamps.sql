-- V4: add audit timestamp columns to shelter table
-- V1 creates shelter with created_at/updated_at, but the live DB was provisioned from an
-- earlier schema state that omitted them, causing:
--   ERROR: column s1_0.created_at does not exist
-- IF NOT EXISTS makes this safe whether or not the columns already exist.

ALTER TABLE shelter
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
