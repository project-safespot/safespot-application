-- V3: make shelter coordinates and capacity nullable
-- latitude/longitude were NOT NULL but active shelter sources lack reliable WGS84 coordinates:
--   SEOUL_SHELTER_LANDSLIDE has no coordinate fields.
--   SEOUL_SHELTER_EARTHQUAKE has XCRD/YCRD (Korean projected CRS, not WGS84) and LOT (longitude only, no LAT).
-- capacity was NOT NULL but SEOUL_SHELTER_EARTHQUAKE has no capacity field.
-- 0,0 rows were written by the pre-fix normalizer as a placeholder; cleaned up here.

ALTER TABLE shelter ALTER COLUMN latitude  DROP NOT NULL;
ALTER TABLE shelter ALTER COLUMN longitude DROP NOT NULL;
ALTER TABLE shelter ALTER COLUMN capacity  DROP NOT NULL;

UPDATE shelter SET latitude = NULL, longitude = NULL WHERE latitude = 0 AND longitude = 0;
