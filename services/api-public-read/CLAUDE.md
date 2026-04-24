# api-public-read Service Guide

## 1. Responsibility

`api-public-read` owns public read endpoints, Redis-first reads, RDS fallback, suppress-window handling, and cache regeneration request events.

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

- pointer: `disaster:latest:{disasterType}:{region}`
- detail: `disaster:detail:{alertId}`

Miss rules:

- pointer miss -> request pointer regeneration
- detail miss -> request detail regeneration

## 4. Cache Responsibility Split

- `api-public-read` requests regeneration after fallback
- `async-worker` rebuilds cache data
- do not rebuild Redis directly in this service

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
