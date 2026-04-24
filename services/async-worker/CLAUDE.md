# async-worker Service Guide

## 1. Responsibility

`async-worker` owns SQS consumption, envelope parsing, idempotency handling, cache rebuilds, read-model rebuilds, retry, DLQ, and worker observability.

It does not own API response handling, admin writes, or external collection.

## 2. Required Policies

- MUST follow the repository-wide Seoul MVP region policy
- MUST keep requests and cache namespaces Seoul-only in the MVP
- MUST enforce `UNSUPPORTED_REGION` consistency across async cache behavior and region namespace handling
- this is not optional

## 3. Cache Model

Shelter:

- current key: `shelter:status:{shelterId}`

Disaster message read models:

- `disaster:detail:{alertId}`
- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`

Required rebuild order:

1. `disaster:detail:{alertId}`
2. `disaster:messages:recent:seoul`
3. `disaster:message:core:seoul`
4. `disaster:messages:list:seoul`

Worker rules:

- rebuild only from normalized DB data
- do not reclassify raw messages here
- exclude `isInScope=false` messages from public Redis read models
- do not rebuild retired keys such as `disaster:active`, `disaster:latest:*`, or `disaster:alert:list`

Responsibility split:

- `api-core` deletes stale cache when needed
- `api-public-read` requests regeneration after miss, stale detection, or degraded-mode fallback
- `async-worker` rebuilds cache contents
- service-level guides must not override root or `docs/` contracts

## 4. Idempotency Rules

- `EvacuationEntryCreated` -> `entry:{entryId}:ENTERED`
- `EvacuationEntryExited` -> `entry:{entryId}:EXITED`
- no version suffix on those keys
- `CacheRegenerationRequested` stays `cache-regen:{cacheKeyHash}:{windowStart}`

## 5. Current vs Target Awareness

- current implementation may include stubbed regeneration paths
- target architecture uses worker-driven rebuild for documented key families
- keep stub vs target behavior explicit in docs and comments

## 6. Source Documents

- `docs/event/event-envelope.md`
- `docs/event/async-worker.md`
- `docs/api/api-public_read.md`
- `docs/redis-key/redis-key.md`

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-24 | v1.1 | Seoul MVP 지역 정책 준수 문구 강화. `UNSUPPORTED_REGION` 일관성 준수를 선택 사항이 아닌 필수 규칙으로 명시 |
