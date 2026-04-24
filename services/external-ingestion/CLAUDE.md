# external-ingestion Service Guide

## 1. Responsibility

`external-ingestion` owns external collection, raw payload persistence, normalization, RDS writes, and post-commit refresh event publication.

It does not own Redis rebuild execution, public read APIs, admin writes, or worker retry/DLQ execution.

## 2. Required Policies

- Seoul MVP only
- normalized region outputs are Seoul-only in the MVP
- direct Redis writes are not allowed here
- queue names in observability must be logical names, never queue URLs

## 3. Cache And Event Contract

Disaster cache model:

- pointer: `disaster:latest:{disasterType}:{region}`
- detail: `disaster:detail:{alertId}`

Rebuild contract:

- normalized state must support rebuilding both pointer and detail

Publish contract:

- publish after DB commit
- publish must be durable
- preserve the full envelope on failure
- require replayable storage or a failure channel if direct publish cannot complete safely

## 4. Current vs Target Awareness

- current implementation may publish directly after commit
- target architecture still requires the same durability guarantees
- document stubs separately from completed worker-driven rebuild behavior

## 5. Source Documents

- `docs/ingestion/external-ingestion.md`
- `docs/event/event-envelope.md`
- `docs/event/async-worker.md`
- `docs/redis-key/redis-key.md`
