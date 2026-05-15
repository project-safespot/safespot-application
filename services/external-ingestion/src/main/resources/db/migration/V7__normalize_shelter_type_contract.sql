-- V7: normalize shelter disaster_type / shelter_type contract to canonical enums
-- Apply data cleanup first, then enforce canonical CHECK constraints.

UPDATE shelter
SET shelter_type = 'DESIGNATED'
WHERE disaster_type = 'FLOOD'
  AND shelter_type = '지정대피소';

UPDATE shelter
SET shelter_type = 'TEMPORARY'
WHERE disaster_type = 'FLOOD'
  AND shelter_type = '임시대피소';

UPDATE shelter
SET shelter_type = 'WIDE'
WHERE disaster_type = 'EARTHQUAKE'
  AND shelter_type = 'EARTHQUAKE';

UPDATE shelter
SET shelter_type = 'TEMPORARY'
WHERE disaster_type = 'LANDSLIDE'
  AND shelter_type = 'LANDSLIDE';

ALTER TABLE shelter DROP CONSTRAINT IF EXISTS shelter_disaster_type_check;
ALTER TABLE shelter ADD CONSTRAINT shelter_disaster_type_check
    CHECK (disaster_type IN ('EARTHQUAKE', 'FLOOD', 'LANDSLIDE'));

ALTER TABLE shelter DROP CONSTRAINT IF EXISTS shelter_shelter_type_check;
ALTER TABLE shelter ADD CONSTRAINT shelter_shelter_type_check
    CHECK (shelter_type IN ('DESIGNATED', 'TEMPORARY', 'WIDE'));
