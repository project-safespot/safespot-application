# SafeSpot HANDOFF — Codex

## 1. Current State

All services are merged into main:

- api-core
- api-public-read
- external-ingestion
- async-worker

The system is now in **post-merge stabilization phase**.

Focus has shifted from feature implementation → **contract alignment + integration + correctness**.

---

## 2. Core System Model

### Write Path
api-core → RDS (source of truth) → after-commit event publish → async-worker → Redis rebuild

### Read Path
api-public-read → Redis-first → RDS fallback → cache regeneration event

---

## 3. Critical Policies (DO NOT BREAK)

### Capacity
- Over-capacity admission is allowed
- Capacity is NOT a hard limit
- `SHELTER_FULL` must NOT be used

### Region (MVP)
- Current scope: Seoul only
- Outside Seoul → `UNSUPPORTED_REGION`

### Event
- MUST be published AFTER DB commit
- Log-only fallback is NOT acceptable for final design

### Cache Responsibility
- api-core → DEL only (invalidate)
- async-worker → SET / rebuild
- api-public-read → read + fallback + publish regeneration

---

## 4. Service State

### api-core
- Fully merged
- Event publish via `@TransactionalEventListener(AFTER_COMMIT)`
- Redis DEL implemented
- Retry exists but **durability is not guaranteed (known gap)**

### async-worker
- SQS consume + handler dispatch
- Idempotency via Redis SETNX
- Redis SET / read-model rebuild

### api-public-read
- Redis-first read path complete
- pointer/detail cache implemented:
  - `disaster:latest:{type}:{region}`
  - `disaster:detail:{alertId}`
- suppress window (10s, instance-local)

Known gaps:
- `shelter:list:*` cache not implemented
- CacheRegenerationPublisher is stub (log-only)

### external-ingestion
- 10 external APIs integrated
- TX separation done correctly
- after-commit publish implemented

Known gaps:
- Event type mismatch with event-envelope.md
- SQS publisher is stub
- Shelter normalizer incomplete

---

## 5. Critical Gaps (PRIORITY ORDER)

### P0 — Contract Alignment (Docs)
- Remove `SHELTER_FULL` from all docs
- Add `UNSUPPORTED_REGION`
- Align capacity policy across:
  - api-common.md
  - api-core.md
  - api-public-read.md

---

### P1 — Event Contract Alignment
external-ingestion must align with:
- `DisasterDataCollected` (EVENT-005)
- `EnvironmentDataCollected` (EVENT-006)

Replace per-alert events → batch events

---

### P2 — SQS Integration
- Replace all stub publishers:
  - api-public-read
  - external-ingestion
- Use same SQS contract as async-worker

---

### P3 — Cache Completion
- Implement `shelter:list:{region}:{type}:{disasterType}`
- Add publish on miss (api-public-read)
- Ensure worker rebuild logic supports it

---

### P4 — Publisher Refactor
Replace:

publish(String cacheKey)


With:

publish(CacheRegenerationRequest)


Must include:
- eventId
- occurredAt
- idempotencyKey
- cacheKey

---

### P5 — End-to-End Validation
Full flow:

write → event → SQS → worker → Redis → read

Test via:
- local docker-compose (`deploy/`)
- JMeter or similar load test

---

## 6. Important Files

- docs/api/api-common.md
- docs/api/api-core.md
- docs/api/api-public-read.md
- docs/event/event-envelope.md
- docs/redis-key/redis-key.md
- docs/README.md

---

## 7. Guardrails

- Do NOT modify code unless explicitly instructed
- Do NOT introduce new policies
- Do NOT reintroduce SHELTER_FULL
- Do NOT assume future design is implemented
- Do NOT break pointer/detail cache separation

---

## 8. First Task (Recommended Start)

1. Fix documentation contracts:
   - api-common.md
   - api-core.md
   - api-public-read.md
   - README.md

2. Then move to:
   - event contract alignment
   - SQS integration
