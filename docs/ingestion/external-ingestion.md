# External Ingestion

This document defines the `external-ingestion` contract for collection, normalization, and post-write event publication.

## 1. Scope

Current MVP scope is Seoul only.

- external collection and normalization operate only on Seoul data for the MVP
- region-derived outputs must stay within Seoul

## 2. Current Implementation vs Target State

Current implementation:

- external sources are collected on polling loops or CronJobs
- normalized data is written to RDS
- cache refresh events are published after normalization writes

Target architecture:

- the same scope remains, but durability and replay requirements must hold even if the publication mechanism changes

## 3. Cache Contract

Disaster cache follows pointer/detail structure:

- pointer: `disaster:latest:{disasterType}:{region}`
- detail: `disaster:detail:{alertId}`

When disaster data is rebuilt, the contract is that both pointer and detail can be regenerated from normalized RDS state.

Miss handling contract:

- pointer miss -> rebuild pointer
- detail miss -> rebuild detail

## 4. Weather Contract

Weather is region-scoped for the MVP.

- region input is mapped to grid coordinates
- Seoul region -> Seoul grid mapping
- `nx` / `ny` remain the storage and cache selectors

## 5. Event Publication Contract

After normalized data is committed to RDS:

- publish after DB commit
- publish must be durable
- do not allow log-only failure handling
- preserve the full envelope for replay or failure-channel recovery

## 6. Observability

Structured log and metric labels may include `queue_name`, but:

- `queue_name` must be a logical queue name
- never log or label a raw queue URL as `queue_name`

## 7. Responsibility Split

`external-ingestion` owns:

- external API collection
- raw payload persistence
- normalization
- RDS writes
- post-commit event publication

`external-ingestion` does not own:

- direct Redis `SET`
- direct Redis `DEL`
- public read APIs
- worker retry / DLQ execution

## 8. Related Documents

- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- Redis key contract: `docs/redis-key/redis-key.md`
