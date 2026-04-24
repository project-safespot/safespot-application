# Redis Key Contract

This document is the source of truth for Redis key names and cache model rules.

Redis stores derived read data only. RDS remains the source of truth.

## 1. Region Namespace Rule

Current MVP scope is Seoul only.

- use `seoul` as the canonical region namespace for MVP cache keys
- non-Seoul requests must not create new live disaster message namespaces in the MVP
- future regional expansion may add region-specific namespaces later, but current disaster message keys stay Seoul-only

## 2. Disaster Message Read Models

Disaster message read models use these canonical keys:

| Key | Purpose | Notes |
| --- | --- | --- |
| `disaster:messages:recent:seoul` | disaster status / overview page | stores Top 5 recent in-scope messages ordered by `issuedAt DESC` |
| `disaster:message:core:seoul` | global page or menu highlight area | stores the single most important current message |
| `disaster:messages:list:seoul` | disaster message page | stores Top 50 recent in-scope messages ordered by `issuedAt DESC` |
| `disaster:detail:{alertId}` | detail view or expanded read model lookup | stores one message detail payload |

Rules:

- `disasterType` is a payload field, not a Redis key dimension
- `messageCategory` is a payload field, not a Redis key dimension
- `rawType` is preserved as a payload field for UI display and auditability
- Redis list caches are bounded read models, not full history
- RDS remains the full history and source of truth

### 2.1 Common Payload Contract

Every disaster message read model payload item must include at least:

- `schemaVersion`
- `alertId`
- `disasterType`
- `rawType`
- `messageCategory`
- `level`
- `levelRank`
- `region`
- `issuedAt`

Recommended additional fields:

- `title`
- `message`
- `source`
- `sourceRegion`
- `rawLevel`
- `rawLevelTokens`
- `rawCategoryTokens`
- `isInScope`
- `normalizationReason`

`schemaVersion` is required and must be `1`.

### 2.2 `disaster:messages:recent:seoul`

Purpose:

- used by the disaster status or overview page
- may be rendered together with weather and air-quality context, but the key itself stores disaster messages only

Rules:

- Seoul MVP only
- Top 5 recent in-scope messages
- sorted by `issuedAt DESC`

### 2.3 `disaster:message:core:seoul`

Purpose:

- used by the global page or menu highlight area

Selection rule:

- `isInScope = true`
- `levelRank >= 3`
- `messageCategory != CLEAR`
- order by `issuedAt DESC`
- limit `1`

Fallback behavior:

- if no matching message exists, store `null` or an empty payload wrapper with `schemaVersion = 1`
- callers must treat this as "no current core disaster message"

### 2.4 `disaster:messages:list:seoul`

Purpose:

- used by the disaster message page
- supports client-side filtering by payload fields

Payload filter fields:

- `disasterType`
- `messageCategory`
- `level`
- `rawType`

Rules:

- Top `50` only
- not full history
- sorted by `issuedAt DESC`

### 2.5 `disaster:detail:{alertId}`

Purpose:

- stores one disaster message detail payload
- used for detail view or detail expansion from list or recent payloads

## 3. Retired Disaster Keys

The following keys are retired and must not be used as current contract targets:

- `disaster:active`
- `disaster:latest:{disasterType}:{region}`
- `disaster:latest:*`
- `disaster:alert:list`
- `disaster:messages:list:seoul:{type}`
- `disaster:messages:list:seoul:{type}:{category}`
- `disaster:messages:list:seoul:{district}:*`

Policy:

- the active concept is not implemented in MVP
- latest pointer is replaced by the recent/core distinction
- `alert:list` is replaced by `disaster:messages:list:seoul`
- type, category, and district are payload or future-scope concepts, not MVP key dimensions

## 4. Environment Keys

Environment keys remain separate from disaster message keys.

| Key | Purpose |
| --- | --- |
| `environment:weather:seoul` | Seoul MVP weather forecast read model |
| `environment:air-quality:seoul` | Seoul MVP air-quality read model |
| `environment:weather-alert:seoul` | Seoul MVP weather-alert read model |

Rules:

- use `disaster:messages:*` only for disaster message read models
- use `environment:*` only for weather, air quality, or weather alert style environment data
- `env:*` is deprecated historical naming and must not be used as a current Redis contract
- do not mix environment payloads into disaster message key families

## 5. Shelter Key Precision

This document does not redesign shelter keys.

Existing shelter key families remain:

- `shelter:status:{shelterId}`
- `shelter:list:seoul:{shelterType}:{disasterType}`
- `shelter:list:{region}:{shelterType}:{disasterType}`

Precision rule:

- `shelter:list:*` and `disaster:messages:list:seoul` are different list concepts
- shelter list may keep `disasterType` as part of key semantics
- disaster message list must not add `{disasterType}`, `{category}`, or `{district}` key dimensions in MVP

## 6. Miss Handling Rules

- Redis hit -> return cached value
- Redis miss/down/parse error -> use degraded-mode fallback behavior defined by the consuming service when needed
- after degraded-mode fallback or stale detection, publish `CacheRegenerationRequested` subject to the suppress window contract

Disaster message miss rules:

- miss on `disaster:messages:recent:seoul` -> request recent rebuild
- miss on `disaster:message:core:seoul` -> request core rebuild
- miss on `disaster:messages:list:seoul` -> request list rebuild
- miss on `disaster:detail:{alertId}` -> request detail rebuild

## 7. Suppress Window Keys

Suppress keys must be derived from the actual regeneration target key.

Format:

- `suppress:cache-regeneration:{cacheKeyHash}`

Rules:

- hash the exact cache key string that is being requested for regeneration
- do not collapse different cache families into the same suppress key
- suppress keys are duplicate-throttling guards only, not read-model entries

Example:

- miss target: `disaster:messages:list:seoul`
- suppress target: `suppress:cache-regeneration:{hash("disaster:messages:list:seoul")}`

## 8. Endpoint To Redis Mapping

| Consumer or page | Redis key |
| --- | --- |
| disaster overview recent messages | `disaster:messages:recent:seoul` |
| global or menu core message | `disaster:message:core:seoul` |
| disaster message page list | `disaster:messages:list:seoul` |
| disaster detail view | `disaster:detail:{alertId}` |
| shelter status | `shelter:status:{shelterId}` |

## 9. Worker Regeneration Targets

`async-worker` rebuild targets for disaster message read models are:

1. `disaster:detail:{alertId}`
2. `disaster:messages:recent:seoul`
3. `disaster:message:core:seoul`
4. `disaster:messages:list:seoul`

Rules:

- rebuild only from normalized DB data
- do not reclassify raw messages in the worker
- exclude `isInScope = false` records from public disaster message read models
- do not rebuild retired keys
- apply Top N policy when rebuilding recent or list

## 10. Hot Key And Cardinality Notes

MVP intentionally reduces Redis key cardinality by removing `disasterType`, `messageCategory`, and district from disaster message key dimensions.

Benefits:

- simpler regeneration
- better hit ratio
- fewer keys
- lower memory overhead

Risks:

- `disaster:messages:list:seoul` can become a hot key
- payload filtering cost moves to `api-public-read`
- payload size must stay bounded

Mitigation:

- Top N = `50`
- monitor hot key metrics
- monitor payload size
- future expansion may introduce more dimensions if the MVP trade-off stops working

## 11. Ownership Split

- `api-core` = invalidate stale shelter keys by `DEL` where immediate removal is needed
- `api-public-read` = request regeneration after miss, stale detection, or degraded-mode fallback
- `async-worker` = rebuild Redis read models
- `external-ingestion` = writes normalized DB data and may trigger downstream rebuild flow, but does not write Redis directly

## 12. Related Documents

- public read behavior: `docs/api/api-public_read.md`
- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- TTL policy: `docs/redis-key/cache-ttl.md`
