# SafeSpot REST API - api-public-read

This document defines the `api-public-read` API contract.

Common auth, response, error, enum, and validation rules come from `docs/api/api-common.md`.

## 1. Responsibility

`api-public-read` owns:

- public shelter reads
- public disaster reads
- public weather and air-quality reads
- Redis-first reads
- cache regeneration request events
- temporary degraded-mode fallback handling when current implementation cannot serve from Redis

It does not own:

- admin writes
- authentication issuance
- Redis rebuild execution
- external ingestion

## 2. Current Implementation vs Target State

Current implementation:

- read path is Redis first
- some misses or parse failures may still fall back to RDS as a temporary degraded-mode escape hatch
- cache regeneration event is currently documented, but worker-side regeneration for some keys may still be stubbed

Target architecture:

- regeneration requests flow through `EVENT-007`
- normal public hot path does not depend on RDS
- workers rebuild the requested cache entries

## 3. Seoul MVP Validation

Current MVP scope is Seoul only.

- non-Seoul `region` -> `400 UNSUPPORTED_REGION`
- valid `lat` / `lng` outside Seoul -> `400 UNSUPPORTED_REGION`
- invalid `lat` / `lng` format -> `400 VALIDATION_ERROR`

## 4. Cache Model

### 4.1 Disaster cache

Canonical disaster message read models:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

Rules:

- `api-public-read` is Redis-first
- disaster read models are rebuilt by `async-worker`, not by this service
- `disasterType` and `messageCategory` are payload fields, not Redis key dimensions
- filtering for disaster message list reads is payload-based

Miss handling:

- recent miss -> publish recent regeneration request
- core miss -> publish core regeneration request
- list miss -> publish list regeneration request
- detail miss -> publish detail regeneration request

### 4.2 Shelter cache

Current key:

- `shelter:status:{id}`

Future list keys:

- `shelter:list:seoul:*`
- `shelter:list:{region}:*`

### 4.3 Cache regeneration

Current implementation:

- regeneration request behavior exists at the API contract level
- some regeneration flow remains a stub depending on key family

Target architecture:

- `EVENT-007` drives worker rebuild

## 5. Endpoints

### 5.1 GET /shelters/nearby

Purpose: return nearby shelters for Seoul coordinates.

Query parameters:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `lat` | number | Y | valid coordinate within Seoul scope |
| `lng` | number | Y | valid coordinate within Seoul scope |
| `radius` | number | Y | `100` to `5000` |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "shelterId": 101,
        "shelterName": "Mapo Gymnasium Shelter",
        "disasterType": "EARTHQUAKE",
        "address": "Seoul Mapo-gu ...",
        "latitude": 37.5687,
        "longitude": 126.9081,
        "distanceM": 420,
        "capacityTotal": 120,
        "currentOccupancy": 128,
        "availableCapacity": 0,
        "congestionLevel": "FULL",
        "shelterStatus": "OPERATING",
        "updatedAt": "2026-04-14T09:10:00+09:00"
      }
    ]
  }
}
```

Notes:

- `congestionLevel` is informational only.
- `FULL` does not prevent admission.

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing required field | 400 | `MISSING_REQUIRED_FIELD` |
| Invalid format/range | 400 | `VALIDATION_ERROR` |
| Outside Seoul | 400 | `UNSUPPORTED_REGION` |

### 5.2 GET /shelters/{shelterId}

Response `200`:

```json
{
  "success": true,
  "data": {
    "shelterId": 101,
    "shelterName": "Mapo Gymnasium Shelter",
    "disasterType": "EARTHQUAKE",
    "address": "Seoul Mapo-gu ...",
    "latitude": 37.5687,
    "longitude": 126.9081,
    "capacityTotal": 120,
    "currentOccupancy": 128,
    "availableCapacity": 0,
    "congestionLevel": "FULL",
    "shelterStatus": "OPERATING",
    "manager": "Kim Admin",
    "contact": "02-123-4567",
    "note": "Basement level 1",
    "updatedAt": "2026-04-14T09:10:00+09:00"
  }
}
```

### 5.3 GET /disaster-alerts

Query parameters:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | Seoul-only |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "alertId": 55,
        "disasterType": "FLOOD",
        "region": "Seoul",
        "level": "WARNING",
        "message": "River level warning",
        "issuedAt": "2026-04-14T08:55:00+09:00",
        "expiredAt": null
      }
    ]
  }
}
```

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Invalid enum | 400 | `VALIDATION_ERROR` |
| Outside Seoul | 400 | `UNSUPPORTED_REGION` |

Redis read model reference:

- primary key: `disaster:messages:list:seoul`
- filtering by `disasterType` is applied on payload items
- `messageCategory`, `level`, and `rawType` may also be filtered from payload fields
- this key is a Top N read model, not full history

### 5.4 GET /disasters/{disasterType}/latest

Query parameters:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | Y | Seoul-only |

Behavior:

- disaster latest-style reads must use the canonical disaster message read models rather than a `{disasterType}` pointer key
- selection comes from payload fields in `disaster:messages:list:seoul` or `disaster:messages:recent:seoul`
- detail expansion uses `disaster:detail:{alertId}`
- do not introduce `{disasterType}` as a Redis key dimension for MVP disaster message caches

Response `200`:

```json
{
  "success": true,
  "data": {
    "alertId": 55,
    "disasterType": "EARTHQUAKE",
    "region": "Seoul",
    "level": "WARNING",
    "message": "Seoul earthquake warning",
    "issuedAt": "2026-04-14T08:55:00+09:00",
    "expiredAt": null,
    "details": {
      "magnitude": 4.3,
      "epicenter": "North Gyeonggi",
      "intensity": "IV"
    }
  }
}
```

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing `region` | 400 | `MISSING_REQUIRED_FIELD` |
| Invalid enum | 400 | `VALIDATION_ERROR` |
| Outside Seoul | 400 | `UNSUPPORTED_REGION` |
| Not found | 404 | `NOT_FOUND` |

### 5.5 GET /weather-alerts

Supported inputs:

- region-only Seoul lookup
- optional grid lookup using `nx` / `ny`

Weather API rules:

- Seoul-only region validation applies
- `nx` / `ny` must be valid grid coordinates when supplied
- region input is mapped to a Seoul grid

Query parameters:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | Seoul-only |
| `nx` | number | N | valid grid x |
| `ny` | number | N | valid grid y |

At least one of `region` or `nx` + `ny` must be supplied.

Response `200`:

```json
{
  "success": true,
  "data": {
    "region": "Seoul",
    "nx": 60,
    "ny": 127,
    "temperature": 18.5,
    "weatherCondition": "CLEAR",
    "forecastedAt": "2026-04-15T15:00:00+09:00"
  }
}
```

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing selector | 400 | `MISSING_REQUIRED_FIELD` |
| Invalid `nx` / `ny` | 400 | `VALIDATION_ERROR` |
| Outside Seoul | 400 | `UNSUPPORTED_REGION` |

### 5.6 GET /air-quality

Query parameters:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | Seoul-only |
| `stationName` | string | N | Seoul station name |

At least one of `region` or `stationName` must be supplied.

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing selector | 400 | `MISSING_REQUIRED_FIELD` |
| Outside Seoul | 400 | `UNSUPPORTED_REGION` |

## 6. Fallback And Regeneration Rules

- Redis hit -> return cached value
- Redis miss/stale/parse failure -> publish regeneration request subject to suppress window
- if the current implementation cannot serve from Redis, degraded-mode fallback to RDS may be used temporarily
- degraded-mode fallback is not target hot-path behavior

`EVENT-007` status:

- current: contract documented, some regeneration paths may still be stubbed
- target: worker receives and rebuilds requested cache entries

Disaster cache regeneration rules:

- `api-public-read` may publish `CacheRegenerationRequested`
- `api-public-read` must not call Redis `SET` to rebuild read models directly
- `async-worker` owns rebuild of `disaster:messages:recent:seoul`, `disaster:message:core:seoul`, `disaster:messages:list:seoul`, and `disaster:detail:{alertId}`
- normal hot-path reads should not depend on RDS, even though RDS remains the source of truth

## 7. Related Documents

- common API rules: `docs/api/api-common.md`
- event envelope: `docs/event/event-envelope.md`
- worker behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
