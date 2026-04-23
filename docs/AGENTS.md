# AGENTS.md

## Scope
Applies to all files under `docs/`.

---

## Purpose
Docs are contract artifacts, not notes.
They define system behavior across services.

---

## Core Rules

1. Do NOT treat docs as optional descriptions.
2. Docs must be internally consistent.
3. Docs must be consistent with each other.

---

## Required Consistency Checks

When editing a document, always check:

- Related API docs
- Event contracts
- Redis key docs
- README index
- Root CLAUDE.md / AGENTS.md references

---

## Broken Reference Handling

If a document references:

- a missing file
- a wrong path

You MUST:

- fix the path if correct file exists
- OR remove/mark it clearly if missing

Do NOT leave broken references.

---

## Policy Alignment Rules

### Capacity
- Never describe capacity as a hard limit
- Never reintroduce `SHELTER_FULL`

### Region
- Current scope is Seoul MVP
- Non-Seoul → `UNSUPPORTED_REGION`

### Validation
Clearly separate:
- invalid format/value
- missing required field
- unsupported region

---

## Cache Documentation Rules

When cache is involved:

- Distinguish current implementation vs future design
- Do NOT assume future design is already implemented
- If pointer/detail is used, do NOT collapse them into one

---

## Event Documentation Rules

- Events are AFTER-COMMIT only
- Do NOT describe pre-commit publishing
- If implementation is incomplete, distinguish:
  - current state
  - target contract

---

## Editing Style

- Prefer explicit policy statements
- Avoid vague wording
- Avoid duplicated conflicting explanations
- Keep structure stable unless necessary

---

## Forbidden

- Reintroducing removed contracts (e.g., SHELTER_FULL)
- Leaving inconsistent terminology across docs
- Writing assumptions not present in source docs
