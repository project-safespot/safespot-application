# docs

# SafeSpot Docs

SafeSpot application 문서 인덱스입니다. 문서는 역할별로 분리하며, 공통 기준을 먼저 읽고 각 서비스별 상세 문서를 참조합니다.

## 읽는 순서

1. [REST API 공통 명세](api/api-common.md)
2. [api-core API 명세](api/api-core.md)
3. [api-public-read API 명세](api/api-public-read.md)
4. [이벤트 Envelope 명세](event/event-envelope.md)
5. [비동기 Worker 명세](async/async-worker.md)
6. [Monitoring 명세](monitoring/monitoring.md)

## API 문서

| 문서 | 설명 |
| --- | --- |
| [api-common.md](api/api-common.md) | 인증 정책, 공통 응답 구조, 공통 에러 코드, enum, validation, 보안/로그 기준 |
| [api-core.md](api/api-core.md) | 인증 API, 관리자 API, 관리자 write, 이벤트 발행, Redis 캐시 무효화 기준 |
| [api-public-read.md](api/api-public-read.md) | 공개 조회 API, Redis 우선 조회, RDS fallback, cache regeneration 요청 기준 |

## Event / Async 문서

| 문서 | 설명 |
| --- | --- |
| [event-envelope.md](event/event-envelope.md) | 공통 event envelope, event type, idempotencyKey 구성 규칙, 이벤트별 SQS payload |
| [async-worker.md](async/async-worker.md) | SQS/Lambda worker 처리 흐름, Redis SET 포맷, 재시도/DLQ 정책, worker 책임 경계 |

## Monitoring 문서

| 문서 | 설명 |
| --- | --- |
| [monitoring.md](monitoring/monitoring.md) | Application 및 Async-worker 개발자가 구현해야 할 metric/log 기준, dashboard, alert 기준 |

## Data / Redis 문서

| 문서 | 설명 |
| --- | --- |
| [db-schema.md](data/db-schema.md) | SafeSpot RDS schema 기준 |
| [redis-key.md](redis-key/redis-key.md) | Redis key naming 및 사용 기준 |
| [cache-ttl.md](redis-key/cache-ttl.md) | Redis cache TTL 기준 |

## Ingestion 문서

| 문서 | 설명 |
| --- | --- |
| [external-ingestion.md](ingestion/external-ingestion.md) | 외부 API 수집, 정규화, source별 수집 방식 및 스케줄 기준 |

## Git / 운영 문서

| 문서 | 설명 |
| --- | --- |
| [git-workflow.md](git-workflow.md) | 브랜치, PR, 작업 흐름 기준 |

## 문서 관리 원칙

- 공통 정책은 `api/api-common.md`에 둔다.
- 개별 endpoint 상세는 `api/api-core.md`, `api/api-public-read.md`에 둔다.
- 이벤트 payload와 idempotencyKey는 `event/event-envelope.md`를 기준으로 한다.
- Worker 내부 처리, 재시도, DLQ, Redis SET 결과 포맷은 `async/async-worker.md`를 기준으로 한다.
- Application/Async-worker metric과 log는 `monitoring/monitoring.md`를 기준으로 한다.
- 문서 간 내용이 충돌하면 더 구체적인 책임 문서를 우선하되, 공통 정책과 충돌하는 경우 공통 문서를 먼저 수정한다.
