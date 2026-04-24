# SafeSpot Event Envelope

This document defines the shared event envelope, idempotency rules, and event examples.

Worker behavior belongs in `docs/event/async-worker.md`.

## 1. Common Envelope

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED",
  "payload": {}
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `eventId` | string | unique event ID |
| `eventType` | string | contract event type |
| `occurredAt` | string | event time |
| `producer` | string | producing service |
| `traceId` | string | trace ID |
| `idempotencyKey` | string | dedupe key |
| `payload` | object | event payload |

## 2. Publish Durability Requirement

All producers must follow these rules:

- publish only after DB commit
- publish must be durable
- log-only failure handling is not acceptable
- on failure, the full envelope must be preserved for replay or recovery
- replayable storage or a failure channel is required when direct publish cannot complete safely

## 3. Idempotency Rules

Current canonical keys:

| Event | idempotencyKey |
| --- | --- |
| `EvacuationEntryCreated` | `entry:{entryId}:ENTERED` |
| `EvacuationEntryExited` | `entry:{entryId}:EXITED` |
| `EvacuationEntryUpdated` | `entry:{entryId}:UPDATED:{eventId}` |
| `ShelterUpdated` | `shelter:{shelterId}:UPDATED:{eventId}` |
| `DisasterDataCollected` | `collected:disaster:{collectionType}:{region}:{completedAt}` |
| `EnvironmentDataCollected` | `collected:env:{collectionType}:{region}:{timeWindow}` |
| `CacheRegenerationRequested` | `cache-regen:{cacheKeyHash}:{windowStart}` |

Version suffixes are not used for `ENTERED` and `EXITED`.

For `CacheRegenerationRequested`, `cacheKeyHash` must be derived from the exact `cacheKey` target.

`collected:env:*` is an event idempotency namespace only. Redis environment read-model keys use `environment:*`.

## 4. Event Types

### EVENT-001 `EvacuationEntryCreated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "ENTERED",
    "recordedByAdminId": 7,
    "enteredAt": "2026-04-15T14:00:00+09:00"
  }
}
```

### EVENT-002 `EvacuationEntryExited`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryExited",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:EXITED",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "EXITED",
    "recordedByAdminId": 7,
    "exitedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

### EVENT-003 `EvacuationEntryUpdated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryUpdated",
  "occurredAt": "2026-04-15T15:20:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:UPDATED:uuid-v4",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "recordedByAdminId": 7,
    "updatedAt": "2026-04-15T15:20:00+09:00",
    "changedFields": [
      "address",
      "familyInfo",
      "specialProtectionFlag"
    ]
  }
}
```

### EVENT-004 `ShelterUpdated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "ShelterUpdated",
  "occurredAt": "2026-04-15T15:30:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "shelter:101:UPDATED:uuid-v4",
  "payload": {
    "shelterId": 101,
    "recordedByAdminId": 7,
    "updatedAt": "2026-04-15T15:30:00+09:00",
    "changedFields": [
      "capacityTotal",
      "shelterStatus",
      "note"
    ]
  }
}
```

### EVENT-005 `DisasterDataCollected`

```json
{
  "eventId": "uuid-v4",
  "eventType": "DisasterDataCollected",
  "occurredAt": "2026-04-15T15:02:00+09:00",
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:disaster:FLOOD:seoul:2026-04-15T15:02:00+09:00",
  "payload": {
    "collectionType": "FLOOD",
    "region": "seoul",
    "affectedAlertIds": [55, 56],
    "hasExpiredAlerts": true,
    "completedAt": "2026-04-15T15:02:00+09:00"
  }
}
```

### EVENT-006 `EnvironmentDataCollected`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EnvironmentDataCollected",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:env:AIR_QUALITY:seoul:2026-04-15T15:00",
  "payload": {
    "collectionType": "AIR_QUALITY",
    "region": "seoul",
    "completedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

### EVENT-007 `CacheRegenerationRequested`

Purpose: request async Redis read model regeneration after a read-path cache miss, stale detection, or downstream rebuild trigger.

Rules:

- this event requests a Redis read model rebuild
- it does not imply `api-public-read` directly writes Redis
- `async-worker` owns rebuild execution
- `api-public-read` may publish the request on cache miss or stale detection
- `external-ingestion` may trigger downstream regeneration after normalized DB writes, depending on the event flow

```json
{
  "eventId": "uuid-v4",
  "eventType": "CacheRegenerationRequested",
  "occurredAt": "2026-04-15T15:05:00+09:00",
  "producer": "api-public-read",
  "traceId": "uuid-v4",
  "idempotencyKey": "cache-regen:sha256(disaster:messages:list:seoul):1744980300",
  "payload": {
    "cacheKey": "disaster:messages:list:seoul",
    "cacheKeyFamily": "disaster_messages_list",
    "requestedAt": "2026-04-15T15:05:00+09:00",
    "reason": "cache_miss",
    "schemaVersion": 1
  }
}
```

Payload fields:

| Field | Meaning |
| --- | --- |
| `cacheKey` | exact Redis target key |
| `cacheKeyFamily` | logical read-model family |
| `requestedAt` | request creation time |
| `reason` | why regeneration was requested |
| `schemaVersion` | event payload contract version |

Recommended `cacheKeyFamily` values:

- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`
- `disaster_detail`
- `shelter_status`
- `shelter_list`
- `environment_weather`
- `environment_air_quality`
- `environment_weather_alert`

Supported disaster message cache targets:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

Supported environment cache targets:

- `environment:weather:seoul`
- `environment:air-quality:seoul`
- `environment:weather-alert:seoul`

Retired disaster keys are not supported `CacheRegenerationRequested` targets.

## 5. Related Documents

- async worker behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
