# api-public-read Service Guide

## 1. Responsibility

`api-public-read` owns public read endpoints, Redis-first reads, degraded-mode RDS fallback, suppress-window handling, payload-based filtering, and cache regeneration request events.

It does not own admin writes, auth issuance, external ingestion, or cache rebuild execution.

## 2. Required Policies

- Seoul MVP only
- requests outside Seoul must return `400 UNSUPPORTED_REGION`
- valid coordinates outside Seoul still return `UNSUPPORTED_REGION`
- capacity is not a rejection condition
- `congestionLevel` is informational only

## 3. Cache Model

Shelter:

- current key: `shelter:status:{shelterId}`
- future key families: `shelter:list:seoul:*`, `shelter:list:{region}:*`

Disaster:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

Miss rules:

- recent miss -> request recent regeneration
- core miss -> request core regeneration
- list miss -> request list regeneration
- detail miss -> request detail regeneration

Read rules:

- disaster message filtering is payload-based, not Redis-key-based
- do not add `{disasterType}`, `{category}`, or `{district}` key dimensions in MVP disaster message caches
- normal public read path must not depend on RDS
- direct RDS fallback is degraded-mode only

## 4. Cache Responsibility Split

- `api-public-read` requests regeneration after miss, stale detection, or degraded-mode fallback
- `async-worker` rebuilds cache data
- do not rebuild Redis directly in this service
- do not call Redis `SET` to rebuild public read models

## 5. Current vs Target Awareness

- current implementation may include regeneration stubs
- target architecture uses `EVENT-007` worker-driven regeneration
- document both states separately when behavior is incomplete

## 6. Source Documents

- `docs/api/api-common.md`
- `docs/api/api-public_read.md`
- `docs/event/event-envelope.md`
- `docs/event/async-worker.md`
- `docs/redis-key/redis-key.md`

Service-level guides must not override root or `docs/` contracts.
