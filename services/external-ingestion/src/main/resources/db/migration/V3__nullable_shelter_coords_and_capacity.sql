-- V3: make shelter coordinates and capacity nullable
-- latitude/longitude were NOT NULL, but some active shelter sources do not expose
-- reliable WGS84 coordinates. SEOUL_SHELTER_LANDSLIDE has no coordinate fields.
-- capacity is not guaranteed by every source, so it must remain nullable.
-- 0,0 rows were written by the pre-fix normalizer as a placeholder; cleaned up here.

ALTER TABLE shelter ALTER COLUMN latitude  DROP NOT NULL;
ALTER TABLE shelter ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE shelter ALTER COLUMN capacity  DROP NOT NULL;

UPDATE shelter SET latitude = NULL, longitude = NULL WHERE latitude = 0 AND longitude = 0;
