# api-core Service Guide

## 1. Responsibility

`api-core` owns authentication, admin reads, admin writes, audit logging, post-commit event publication, and Redis invalidation by `DEL` only.

It does not own public read APIs, Redis rebuild execution, external ingestion, or worker retry/DLQ behavior.

## 2. Required Policies

- Seoul MVP only
- requests outside Seoul must return `400 UNSUPPORTED_REGION`
- over-capacity admission is allowed
- capacity is not a rejection condition
- `congestionLevel` is a state indicator only

## 3. Event Rules

- publish only after DB commit
- publish must be durable
- do not allow log-only failure handling
- preserve the full envelope in replayable storage or a failure channel when direct publish cannot complete safely

Idempotency rules:

- `EvacuationEntryCreated` -> `entry:{entryId}:ENTERED`
- `EvacuationEntryExited` -> `entry:{entryId}:EXITED`
- no version suffix on those keys

## 4. Cache Responsibility Split

- `api-core` invalidates stale keys by `DEL`
- `async-worker` rebuilds cache contents
- do not implement Redis `SET` rebuilds in `api-core`

High-level cache model to stay aligned with docs:

- current shelter key: `shelter:status:{shelterId}`
- disaster recent read model: `disaster:messages:recent:seoul`
- disaster core read model: `disaster:message:core:seoul`
- disaster list read model: `disaster:messages:list:seoul`
- disaster detail read model: `disaster:detail:{alertId}`
- `api-core` does not rebuild or write disaster read models

## 5. Current vs Target Awareness

When implementation differs from the target architecture:

- document current behavior explicitly
- document target durable event architecture explicitly
- do not blur stub behavior with completed behavior

## 6. Source Documents

- `docs/api/api-common.md`
- `docs/api/api-core.md`
- `docs/event/event-envelope.md`
- `docs/event/async-worker.md`
- `docs/redis-key/redis-key.md`
