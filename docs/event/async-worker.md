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

## 2. Current Implementation vs Target State

Current implementation:

- workers consume committed events
- some regeneration paths may still be stubbed

Target architecture:

- worker-driven rebuild handles all documented cache families
- durability requirements from the event envelope remain unchanged

## 3. Cache Ownership Split

- `api-core` invalidates stale keys by `DEL` only
- `api-public-read` requests regeneration after fallback
- `async-worker` rebuilds cache contents

## 4. Key Families

Shelter:

- `shelter:status:{shelterId}`

Disaster pointer/detail:

- pointer: `disaster:latest:{disasterType}:{region}`
- detail: `disaster:detail:{alertId}`

Environment:

- `env:weather:{nx}:{ny}`
- `env:air:{station_name}`

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
- `congestionLevel` is informational only
- capacity does not reject admission

### 5.2 Disaster rebuild

Triggers:

- `DisasterDataCollected`
- `CacheRegenerationRequested` for disaster keys

Behavior:

- rebuild pointer key `disaster:latest:{disasterType}:{region}`
- rebuild detail key `disaster:detail:{alertId}`
- pointer miss and detail miss are handled separately

### 5.3 Environment rebuild

Triggers:

- `EnvironmentDataCollected`

Behavior:

- rebuild `env:weather:{nx}:{ny}` or `env:air:{station_name}`

## 6. EVENT-007

Current:

- contract exists
- some paths may still be stubbed

Target:

- `api-public-read` emits regeneration request
- worker rebuilds the requested key family

## 7. Retry And DLQ

- invalid payloads go to DLQ
- transient Redis or RDS failures are retried
- partial batch failure is allowed
- full envelope metadata must remain available for investigation and replay

## 8. Related Documents

- `docs/event/event-envelope.md`
- `docs/api/api-public_read.md`
- `docs/redis-key/redis-key.md`
