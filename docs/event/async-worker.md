# Async Worker

This document defines async worker behavior, retry/DLQ rules, and cache rebuild ownership.

## 1. Responsibility

`async-worker` owns:

- SQS event consumption
- envelope parsing
- idempotency handling
- Redis rebuilds
- retry and DLQ handling

It does not own:

- API request handling
- admin writes
- external API collection
- disaster message reclassification

## 2. Current Implementation vs Target State

Current implementation:

- workers consume committed events
- some regeneration paths may still be stubbed

Target architecture:

- worker-driven rebuild handles all documented cache families
- disaster message rebuild uses normalized DB data only
- durability requirements from the event envelope remain unchanged

## 3. Cache Ownership Split

- `api-core` invalidates stale shelter keys by `DEL` only
- `api-public-read` requests regeneration after fallback or stale detection
- `async-worker` rebuilds cache contents

## 4. Key Families

Shelter:

- `shelter:status:{shelterId}`
- `shelter:list:seoul:{shelterType}:{disasterType}` (near-term planned contract, not fully implemented yet)
- `shelter:list:{region}:{shelterType}:{disasterType}` (near-term planned contract, not fully implemented yet)

Disaster message read models:

- `disaster:detail:{alertId}`
- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`

Environment:

- `environment:weather:{nx}:{ny}`
- `environment:weather:region:{region}`
- `environment:air:{stationName}`

Retired disaster keys must not be rebuilt.

## 5. Rebuild Behavior

### 5.1 Shelter rebuild

Triggers:

- `EvacuationEntryCreated`
- `EvacuationEntryExited`
- `EvacuationEntryUpdated`
- `ShelterUpdated`
- `CacheRegenerationRequested` for shelter keys

Behavior:

- read RDS state
- rebuild `shelter:status:{shelterId}`
- near-term planned contract: `CacheRegenerationRequested` may also rebuild `shelter:list:seoul:{shelterType}:{disasterType}` and `shelter:list:{region}:{shelterType}:{disasterType}`
- `congestionLevel` is informational only
- capacity does not reject admission

### 5.2 Disaster message rebuild

Triggers:

- `DisasterDataCollected`
- `CacheRegenerationRequested` for disaster message keys

Required regeneration order after a new in-scope disaster message:

1. `disaster:detail:{alertId}`
2. `disaster:messages:recent:seoul`
3. `disaster:message:core:seoul`
4. `disaster:messages:list:seoul`

Behavior:

- read normalized DB data only
- do not reclassify raw messages in the worker
- exclude `isInScope = false` records from public disaster Redis read models
- apply Top 5 policy when rebuilding `disaster:messages:recent:seoul`
- apply Top 50 policy when rebuilding `disaster:messages:list:seoul`
- `disaster:message:core:seoul` selects one row using `isInScope = true`, `levelRank >= 3`, `messageCategory != CLEAR`, `issuedAt DESC`
- if no core candidate exists, write `null` or an empty payload wrapper with `schemaVersion = 1`
- do not rebuild retired keys such as `disaster:active`, `disaster:latest:*`, or `disaster:alert:list`

### 5.3 Environment rebuild

Triggers:

- `EnvironmentDataCollected`
- `CacheRegenerationRequested` for environment keys

Behavior:

- rebuild `environment:weather:{nx}:{ny}`, `environment:weather:region:{region}`, or `environment:air:{stationName}`

## 6. EVENT-007 Handling

Current:

- contract exists
- some paths may still be stubbed

Target:

- `api-public-read` emits regeneration request
- worker rebuilds the requested key family
- suppress-window behavior is based on the exact target `cacheKey`

Recommended disaster `cacheKeyFamily` handling:

- `disaster_detail`
- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`

## 7. Retry And DLQ

- invalid payloads go to DLQ
- transient Redis or RDS failures are retried
- partial batch failure is allowed
- full envelope metadata must remain available for investigation and replay

## 8. Related Documents

- `docs/event/event-envelope.md`
- `docs/api/api-public_read.md`
- `docs/redis-key/redis-key.md`
