# SafeSpot Docs

SafeSpot documentation index.

Current MVP scope is Seoul only.

- Requests outside Seoul must return `400 UNSUPPORTED_REGION`.
- Capacity is an operational indicator, not an admission rejection rule.

## Reading Order

1. [Root guide](../CLAUDE.md)
2. [Common API policy](api/api-common.md)
3. [External ingestion](ingestion/external-ingestion.md)
4. [DB schema](data/db-schema.md)
5. [api-core API](api/api-core.md)
6. [Event envelope](event/event-envelope.md)
7. [Redis keys](redis-key/redis-key.md)
8. [Async worker](event/async-worker.md)
9. [Monitoring](monitoring/monitoring.md)

재난 메시지 분류와 정규화 저장 계약은 다음 문서를 함께 본다:

- `docs/api/api-common.md`
- `docs/ingestion/external-ingestion.md`
- `docs/data/db-schema.md`

## API Docs

| Document | Description |
| --- | --- |
| [api-common.md](api/api-common.md) | Common auth, response, error, enum, validation, and policy rules |
| [api-core.md](api/api-core.md) | Admin/auth write-side endpoints and invalidation rules |
| [api-public_read.md](api/api-public_read.md) | Public read endpoints, fallback rules, and cache regeneration requests |

## Event And Async Docs

| Document | Description |
| --- | --- |
| [event-envelope.md](event/event-envelope.md) | Common event envelope, event types, payload examples, and idempotency rules |
| [async-worker.md](event/async-worker.md) | Worker responsibilities, retry/DLQ behavior, and cache rebuild ownership |

## Data And Redis Docs

| Document | Description |
| --- | --- |
| [db-schema.md](data/db-schema.md) | RDS schema reference |
| [redis-key.md](redis-key/redis-key.md) | Redis key naming and cache model rules |
| [cache-ttl.md](redis-key/cache-ttl.md) | Redis TTL policy |

## Ingestion And Operations Docs

| Document | Description |
| --- | --- |
| [external-ingestion.md](ingestion/external-ingestion.md) | External collection, disaster classification normalization, and post-write event contract |
| [monitoring.md](monitoring/monitoring.md) | Application and worker metric/log requirements |
| [git-workflow.md](git-workflow.md) | Branch and review workflow |

## Source Of Truth Rules

- Common API policy belongs in `docs/api/api-common.md`.
- api-core endpoint details belong in `docs/api/api-core.md`.
- api-public-read endpoint details belong in `docs/api/api-public_read.md`.
- Event envelope, event type, payload, and `idempotencyKey` rules belong in `docs/event/event-envelope.md`.
- Async worker behavior, retry/DLQ policy, Redis refresh behavior, and worker responsibility boundaries belong in `docs/event/async-worker.md`.
- Redis key naming and TTL policy belong in `docs/redis-key/redis-key.md` and `docs/redis-key/cache-ttl.md`.
- If documents conflict, prefer the more specific responsibility document, except when it conflicts with common API policy. In that case, update common API policy first.
