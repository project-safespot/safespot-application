BEGIN;

SELECT
    source,
    COUNT(*) AS alert_count
FROM disaster_alert
WHERE source IN (
    'KMA_EARTHQUAKE',
    'SEOUL_EARTHQUAKE',
    'FORESTRY_LANDSLIDE',
    'SEOUL_RIVER_LEVEL'
)
GROUP BY source
ORDER BY source;

DELETE FROM disaster_alert_detail d
USING disaster_alert a
WHERE d.alert_id = a.alert_id
  AND a.source IN (
      'KMA_EARTHQUAKE',
      'SEOUL_EARTHQUAKE',
      'FORESTRY_LANDSLIDE',
      'SEOUL_RIVER_LEVEL'
  );

DELETE FROM disaster_alert
WHERE source IN (
    'KMA_EARTHQUAKE',
    'SEOUL_EARTHQUAKE',
    'FORESTRY_LANDSLIDE',
    'SEOUL_RIVER_LEVEL'
);

ROLLBACK;
