# SafeSpot 문서

SafeSpot 문서 색인.

현재 MVP 범위는 서울만 해당한다.

- 서울 외 요청은 `400 UNSUPPORTED_REGION`을 반환해야 한다.
- capacity는 운영 지표이며 입소 거절 규칙이 아니다.

## 읽는 순서

1. [루트 가이드](../CLAUDE.md)
2. [공통 API 정책](api/api-common.md)
3. [외부 수집](ingestion/external-ingestion.md)
4. [DB 스키마](data/db-schema.md)
5. [Redis 키](redis-key/redis-key.md)
6. [Redis TTL](redis-key/cache-ttl.md)
7. [이벤트 envelope](event/event-envelope.md)
8. [Async worker](event/async-worker.md)
9. [api-public-read API](api/api-public_read.md)
10. [모니터링](monitoring/monitoring.md)

재난 메시지 분류와 정규화 저장 계약은 다음 문서를 함께 본다:

- `docs/api/api-common.md`
- `docs/ingestion/external-ingestion.md`
- `docs/data/db-schema.md`

Redis read model, event, TTL, worker regeneration 계약은 다음 문서를 함께 본다:

- `docs/redis-key/redis-key.md`
- `docs/redis-key/cache-ttl.md`
- `docs/event/event-envelope.md`
- `docs/event/async-worker.md`

## API 문서

| 문서 | 설명 |
| --- | --- |
| [api-common.md](api/api-common.md) | 공통 인증, 응답, 오류, enum, 검증, 정책 규칙 |
| [api-core.md](api/api-core.md) | 관리자/인증 write-side endpoint와 invalidation 규칙 |
| [api-public_read.md](api/api-public_read.md) | public read endpoint, fallback 규칙, cache regeneration 요청 |

## Event 및 Async 문서

| 문서 | 설명 |
| --- | --- |
| [event-envelope.md](event/event-envelope.md) | 공통 event envelope, event type, payload 예시, idempotency 규칙 |
| [async-worker.md](event/async-worker.md) | worker 책임, retry/DLQ 동작, cache rebuild ownership |

## Data 및 Redis 문서

| 문서 | 설명 |
| --- | --- |
| [db-schema.md](data/db-schema.md) | RDS schema reference |
| [redis-key.md](redis-key/redis-key.md) | Redis key naming 및 cache model 규칙 |
| [cache-ttl.md](redis-key/cache-ttl.md) | Redis TTL 정책 |

## Ingestion 및 운영 문서

| 문서 | 설명 |
| --- | --- |
| [external-ingestion.md](ingestion/external-ingestion.md) | 외부 수집, 재난 분류 정규화, write 후 event 계약 |
| [monitoring.md](monitoring/monitoring.md) | Application 및 worker metric/log 요구사항 |
| [git-workflow.md](git-workflow.md) | branch 및 review workflow |

## 기준 문서 규칙

- 공통 API 정책과 API enum은 `docs/api/api-common.md`에 둔다.
- api-core endpoint 상세는 `docs/api/api-core.md`에 둔다.
- api-public-read endpoint 상세는 `docs/api/api-public_read.md`에 둔다.
- 재난 메시지 정규화는 `docs/ingestion/external-ingestion.md`에 둔다.
- DB schema는 API, event, Redis 계약을 따른다. 서로 다르게 재정의하면 안 된다.
- Redis key 이름은 `docs/redis-key/redis-key.md`에 둔다.
- Redis TTL 값은 `docs/redis-key/cache-ttl.md`에 둔다.
- Event envelope, event type, payload, `idempotencyKey` 규칙은 `docs/event/event-envelope.md`에 둔다.
- worker regeneration 동작, retry/DLQ 정책, Redis refresh 동작, worker 책임 경계는 `docs/event/async-worker.md`에 둔다.
- 서비스 수준 `CLAUDE.md`와 `README.md` 파일은 루트 또는 `docs/` 계약을 override하면 안 된다.
- 문서 간 충돌이 있으면 공통 API 정책과 충돌하는 경우를 제외하고 더 구체적인 책임 문서를 우선한다. 공통 API 정책과 충돌하면 공통 API 정책을 먼저 수정한다.
