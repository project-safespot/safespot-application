# AGENTS.md

## Project

SafeSpot disaster evacuation service.

## General rules

- Do not change unrelated files.
- Prefer minimal, reviewable diffs.
- Keep naming consistent with existing docs and Terraform module layout.
- Ask for a plan first on ambiguous or multi-step tasks.
- Do not apply destructive infrastructure changes without explicit confirmation in the thread.

## Git rules

- Work only on the current branch/worktree.
- Never force-push.
- Do not rewrite history.

## Terraform rules

- Follow the module structure under `terraform/modules` and `terraform/environments`.
- Keep variables, outputs, README, and examples aligned.
- Prefer explicit inputs/outputs over hidden coupling.
- Avoid over-broad IAM permissions.
- Flag cost-sensitive resources explicitly: NAT Gateway, RDS Multi-AZ, Redis replicas, public LB.

## Documentation rules

- Use `docs/README.md` as the documentation index.
- Keep documentation changes scoped and reviewable.
- Common API policy belongs in `docs/api/api-common.md`.
- api-core endpoint details belong in `docs/api/api-core.md`.
- api-public-read endpoint details belong in `docs/api/api-public-read.md`.
- Event envelope, event type, payload, and `idempotencyKey` rules belong in `docs/event/event-envelope.md`.
- Async worker behavior, retry/DLQ policy, Redis refresh behavior, and worker responsibility boundaries belong in `docs/async/async-worker.md`.
- Application and async-worker metric/log requirements belong in `docs/monitoring/monitoring.md`.
- Redis key naming and TTL policy belong in `docs/redis-key/redis-key.md` and `docs/redis-key/cache-ttl.md`.
- If documents conflict, prefer the more specific responsibility document, except when it conflicts with common API policy. In that case, update the common policy first.
- When changing event payloads or `idempotencyKey` formats, update both `docs/event/event-envelope.md` and the relevant worker behavior in `docs/async/async-worker.md`.

## Review rules

- Reviewers should not make broad refactors.
- Reviewers should focus on correctness, safety, cost, security, and consistency.
- When possible, comment on diffs rather than rewriting the implementation.
