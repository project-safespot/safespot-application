# AGENTS.md

## Project
SafeSpot monorepo (api-core / api-public-read / external-ingestion).

## Primary Goal
Maintain strict contract consistency across:
- API docs
- service responsibilities
- event contracts
- Redis key contracts
- monitoring contracts

---

## Global Rules

1. Markdown documents are treated as source-of-truth contracts.
2. Do NOT modify code when the task is documentation-only.
3. Prefer minimal, surgical edits over large rewrites.
4. When a shared policy changes, update all affected documents in the same task.
5. Do NOT leave contradictions across documents if they are in scope.
6. Do NOT invent new product policy.
7. Preserve existing document structure and tone unless explicitly instructed.

---

## Current Project Policies (MUST FOLLOW)

### Capacity
- Over-capacity admission is allowed.
- Shelter capacity is NOT a hard limit.
- `SHELTER_FULL` is NOT part of the active contract.

### Region (MVP)
- Current public scope is Seoul.
- Unsupported region must use `UNSUPPORTED_REGION`.

### Events
- Domain events must be published AFTER DB commit.
- Log-only fallback is NOT sufficient (durability expected in contract discussion).

### Cache (Disaster)
- Pointer/detail separation is used where documented:
  - pointer: `disaster:latest:{disasterType}:{region}`
  - detail: `disaster:detail:{alertId}`

---

## Documentation Consistency Targets

When editing any of these, check related documents:

- docs/api/api-common.md
- docs/api/api-core.md
- docs/api/api-public-read.md
- docs/ingestion/external-ingestion.md
- docs/event/event-envelope.md
- docs/redis-key/redis-key.md
- docs/README.md

---

## Out of Scope (unless explicitly requested)

- Code refactoring
- New feature design
- Architecture changes
- Formatting-only global rewrites

---

## Editing Style

- Keep language precise and policy-oriented
- Prefer short declarative sentences
- Avoid ambiguity
- Keep Korean if the original document is Korean
