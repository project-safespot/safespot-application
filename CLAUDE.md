# SafeSpot Repository Guide

## 1. Purpose

This repository contains the SafeSpot disaster evacuation service.

This root guide defines repository-wide policies for:

- service boundaries
- data ownership
- documentation source of truth
- cross-document consistency rules

Implementation details that are specific to one service belong in that service's `CLAUDE.md`.

## 2. Repository Structure

```text
safespot-application/
|-- services/
|   |-- api-core/
|   |-- api-public-read/
|   |-- external-ingestion/
|   `-- async-worker/
|-- docs/
|-- deploy/
|-- AGENTS.md
`-- CLAUDE.md
```

Rules:

- `services/` contains deployable workloads.
- `docs/` is the contract and architecture source of truth.
- `deploy/` contains environment and deployment assets.
- Cross-service changes must update docs first.

## 3. MVP Scope

Current MVP scope is Seoul only.

- Requests outside Seoul must return `400 UNSUPPORTED_REGION`.
- Seoul-only policy must stay aligned across API docs, event docs, ingestion docs, Redis docs, and service guides.
- Valid latitude/longitude outside Seoul are not accepted for the MVP.

## 4. Service Boundaries

### 4.1 api-core

Owns:

- authentication
- admin APIs
- write transactions
- admin audit logs
- event publication after DB commit
- immediate Redis invalidation by `DEL` only for stale keys

Does not own:

- public read APIs
- Redis `SET` / read-model rebuild
- external API ingestion
- worker retry / DLQ processing

### 4.2 api-public-read

Owns:

- public read APIs
- Redis-first read path
- temporary degraded-mode RDS fallback on Redis miss, Redis down, or parse error
- cache regeneration requests on miss, stale, or degraded-mode fallback
- suppress window behavior

Does not own:

- admin writes
- token issuance
- Redis rebuild execution
- Redis `SET` for read-model rebuild
- external API ingestion

### 4.3 external-ingestion

Owns:

- external API collection
- raw payload and raw value preservation
- canonical normalization before DB write
- RDS writes for raw + canonical collected data
- post-normalization event or trigger publication after DB commit

Does not own:

- Redis read-model rebuild
- Redis `SET` for public-read models
- public read APIs
- public-read filtering
- UI rendering
- admin writes
- worker retry / DLQ processing

### 4.4 async-worker

Owns:

- SQS event consumption
- Lambda worker execution
- envelope parsing
- idempotency handling
- Redis rebuilds and read-model rebuilds
- retry / DLQ processing
- worker metrics and logs

Does not own:

- API response handling
- admin write transactions
- external API collection
- event publication from write services

## 5. Data Ownership

- RDS is the source of truth.
- Redis stores derived read data only.
- Redis must never be treated as the authoritative record for entries, audit logs, or event history.

Read path:

```text
Client
-> api-public-read
-> Redis hit
-> response

Redis miss/stale/down/parse error
-> CacheRegenerationRequested
-> temporary degraded-mode RDS fallback only when current implementation cannot serve from Redis
```

Write path:

```text
api-core
-> RDS commit
-> durable event publish
-> async-worker
-> Redis rebuild
```

## 6. Event Durability Rule

All event-producing documents and implementations must follow this rule:

- events are published only after DB commit
- publication must be durable
- log-only failure handling is not acceptable
- if immediate publish cannot complete reliably, the system must use replayable storage or a failure channel that preserves the full envelope for recovery

Current implementation and target architecture must be documented separately when they differ.

## 7. Cache Model

### 7.1 Shelter cache

Current model:

- `shelter:status:{shelterId}`

Target list model:

- `shelter:list:seoul:*`
- `shelter:list:{region}:*`

Responsibility split:

- `api-core` invalidates by `DEL` only
- `async-worker` rebuilds cache contents

### 7.2 Disaster cache

Current disaster message read models:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

Rules:

- `disasterType`, `messageCategory`, and district are not Redis key dimensions for MVP disaster message lists.
- filtering is payload-based in `api-public-read`.
- RDS stores full history and remains the source of truth.
- `api-public-read` requests regeneration on miss, stale, or degraded-mode fallback.
- `async-worker` rebuilds Redis read models from normalized DB data.

## 8. Documentation Source Of Truth

Documentation index: `docs/README.md`

| Area | Source of truth |
| --- | --- |
| Common API policy | `docs/api/api-common.md` |
| api-core endpoints | `docs/api/api-core.md` |
| api-public-read endpoints | `docs/api/api-public_read.md` |
| Event envelope / payload / idempotency | `docs/event/event-envelope.md` |
| Async worker behavior / retry / DLQ | `docs/event/async-worker.md` |
| Monitoring metrics / logs | `docs/monitoring/monitoring.md` |
| Redis keys / TTL | `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md` |
| External ingestion | `docs/ingestion/external-ingestion.md` |
| RDS schema | `docs/data/db-schema.md` |
| Git workflow | `docs/git-workflow.md` |

Service-level `CLAUDE.md` and `README.md` files must not override root or `docs/` contracts.

If documents conflict:

- prefer the more specific responsibility document
- except when it conflicts with common API policy, in which case update `docs/api/api-common.md` first

## 9. Cross-Document Update Rules

- Common response, error code, enum, and validation changes must update `docs/api/api-common.md`.
- api-core endpoint changes must update `docs/api/api-core.md`.
- api-public-read endpoint changes must update `docs/api/api-public_read.md`.
- Event envelope, event type, payload, and `idempotencyKey` changes must update `docs/event/event-envelope.md`.
- Async worker behavior, retry, DLQ, and cache rebuild responsibilities must update `docs/event/async-worker.md`.
- Redis key naming or TTL changes must update `docs/redis-key/redis-key.md` and any referencing docs.
- If event payloads or `idempotencyKey` formats change, update both event and async-worker docs.
- When current implementation differs from the target architecture, both states must be documented explicitly.

## 10. Git Rules

- Work on the current branch/worktree only.
- Do not force-push.
- Do not rewrite history.
- Keep diffs minimal and reviewable.
- Do not change unrelated files.

## 11. Prohibited Patterns

- Treating capacity as an API rejection condition
- Using capacity-based rejection codes
- Returning capacity-based `409`
- Publishing events before commit
- Accepting event loss with log-only handling
- Writing Redis as source of truth
- Letting read path depend on SQS
- Cross-service code import for convenience
- Treating direct RDS fallback in `api-public-read` as the target hot path
- Using retired disaster keys as active Redis contracts
