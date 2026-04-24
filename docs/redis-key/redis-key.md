# Redis Key Contract

This document defines Redis key naming and cache model rules.

Redis stores derived read data only. RDS remains the source of truth.

## 1. Region Namespace Rule

Current MVP scope is Seoul only.

- use `seoul` as the canonical region namespace for MVP cache keys
- future regional expansion may use `shelter:list:{region}:*`, but Seoul remains the only supported region now
- non-Seoul requests must not create new live region namespaces in the MVP

## 2. Disaster Cache Model

Disaster cache uses pointer/detail structure.

Pointer key:

- `disaster:latest:{disasterType}:{region}`

Detail key:

- `disaster:detail:{alertId}`

Rules:

- pointer miss -> publish pointer regeneration request
- detail miss -> publish detail regeneration request
- pointer values identify the latest `alertId`
- detail values contain the full alert body

Example:

```json
// key: disaster:latest:EARTHQUAKE:seoul
{
  "alertId": 55
}
```

```json
// key: disaster:detail:55
{
  "alertId": 55,
  "disasterType": "EARTHQUAKE",
  "region": "seoul",
  "level": "WARNING",
  "message": "Seoul earthquake warning",
  "issuedAt": "2026-04-14T08:55:00+09:00",
  "expiredAt": null
}
```

## 3. Shelter Cache Model

Current implementation:

- `shelter:status:{shelterId}`

Future target list model:

- `shelter:list:seoul:*`
- `shelter:list:{region}:*`

Rules:

- `api-core` may invalidate stale shelter cache by `DEL`
- worker rebuilds cache contents
- capacity and `congestionLevel` are operational/read indicators only

## 4. Miss Handling Rules

- Redis hit -> return cached value
- Redis miss/down/parse error -> RDS fallback
- after fallback, publish regeneration request subject to suppress window

Disaster-specific miss rules:

- pointer miss -> regenerate pointer
- detail miss -> regenerate detail

## 5. Suppress Window Keys

Suppress window keys must be consistent across docs and implementations.

Format:

- `suppress:cache-regen:{cacheKey}`

Examples:

- `suppress:cache-regen:shelter:status:101`
- `suppress:cache-regen:disaster:latest:EARTHQUAKE:seoul`
- `suppress:cache-regen:disaster:detail:55`

The suppress key is for duplicate-throttling only and is not a source-of-truth cache entry.

## 6. Ownership Split

- `api-core` = invalidate by `DEL` when immediate stale removal is needed
- `api-public-read` = request regeneration after fallback
- `async-worker` = rebuild cache data
- `external-ingestion` = publish post-write refresh events, not direct Redis writes

## 7. Related Documents

- public read behavior: `docs/api/api-public_read.md`
- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- TTL policy: `docs/redis-key/cache-ttl.md`
