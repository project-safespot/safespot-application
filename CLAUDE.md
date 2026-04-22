# SafeSpot Application Repository Guide

## 1. 목적

이 저장소는 SafeSpot 재난 대피 서비스 애플리케이션 monorepo이다.

본 문서는 루트 정책 문서(Global Policy)이며 다음을 정의한다.

- 저장소 구조
- 서비스 경계
- 데이터 원칙
- Git / Worktree 운영 방식
- 문서 Source of Truth
- cross-document update 규칙

구현 상세, 도메인 로직, 서비스별 코드 규칙은 각 서비스 디렉터리의 `CLAUDE.md`가 우선한다.

---

## 2. 저장소 구조

```text
safespot-application/
├── services/
│   ├── api-core/
│   ├── api-public-read/
│   ├── external-ingestion/
│   └── async-worker/
├── docs/
├── deploy/
├── AGENTS.md
└── CLAUDE.md
```

### 구조 원칙

- `services/` 는 실행 단위 deployable workload이다.
- `docs/` 는 구현 계약과 설계의 Source of Truth이다.
- `deploy/` 는 실행, 환경, 배포 스크립트를 둔다.
- 서비스 경계 밖 수정은 최소화하며, cross-service 변경은 문서와 PR로 먼저 합의한다.

---

## 3. packages 모듈 정책

현재 `packages/*` 모듈은 사용하지 않는다.

- `packages/event-schema`
- `packages/common-redis-keys`
- `packages/observability-contract`

각 서비스는 현재 자체 DTO와 구현체를 유지한다. 단, 구현 계약의 기준은 `docs/` 문서이다.

- 이벤트 envelope / payload 계약 기준: `docs/event/event-envelope.md`
- Redis key / TTL 계약 기준: `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md`
- Worker 처리 기준: `docs/async/async-worker.md`

여러 서비스가 동일 계약을 코드 레벨에서 공유해야 할 시점에만 `packages/` 모듈로 promote한다. 현재 단계에서는 조기 추상화를 피한다.

---

## 4. 서비스 경계

### 4.1 api-core

담당:

- 인증
- 관리자 API
- write 트랜잭션
- 관리자 감사 로그
- DB commit 이후 도메인 이벤트 발행
- 즉시 stale 제거가 필요한 Redis 캐시 DEL

담당하지 않음:

- 공개 조회 API
- Redis SET / read model 재생성
- worker retry / DLQ 처리
- 외부 API 수집

### 4.2 api-public-read

담당:

- 공개 조회 API
- Redis 우선 조회
- Redis miss/down/parse error 시 RDS fallback
- fallback 이후 cache regeneration 요청 이벤트 발행
- suppress window 적용

담당하지 않음:

- 관리자 write
- 인증 발급
- Redis SET / cache regeneration 실행
- 외부 API 직접 호출

### 4.3 external-ingestion

담당:

- 외부 API 수집
- raw payload 저장
- 데이터 정규화
- RDS 적재
- 실행 이력 / 정규화 오류 기록
- 정규화 완료 후 cache refresh 이벤트 발행

담당하지 않음:

- Redis 직접 갱신
- 공개 조회 API
- 관리자 write
- async-worker retry / DLQ 처리

### 4.4 async-worker

담당:

- SQS 이벤트 소비
- Lambda worker 실행
- 공통 envelope parsing
- eventType별 handler dispatch
- idempotency 검증
- Redis SET/DEL 기반 cache refresh 및 read model 재구성
- retry / DLQ 처리
- worker-level metric/log

담당하지 않음:

- API 응답 경로 처리
- 관리자 write 트랜잭션
- 이벤트 발행
- 외부 API 수집

---

## 5. 데이터 원칙

### 5.1 Source of Truth

- RDS는 공식 데이터이다.
- Redis는 파생 데이터이며 재생성 가능해야 한다.
- Redis에 원본 데이터나 감사 로그를 source of truth로 저장하지 않는다.

### 5.2 Read Path

```text
Client
-> api-public-read
-> Redis HIT
-> RDS fallback on MISS/down/parse error
```

- Redis 장애는 코드 레벨 fallback으로 처리한다.
- SQS는 read path에 삽입하지 않는다.
- fallback 응답은 동기적으로 반환하고, cache regeneration은 별도 이벤트로 요청한다.

### 5.3 Write Path

```text
api-core
-> RDS commit
-> domain event publish
-> async-worker
-> Redis refresh
```

- write 성공 전 이벤트를 발행하지 않는다.
- 이벤트는 DB commit 이후 발행한다.
- Redis SET은 worker 책임이다.

---

## 6. 문서 Source of Truth

문서 인덱스는 `docs/README.md`이다.

| 영역 | 기준 문서 |
| --- | --- |
| API 공통 정책 | `docs/api/api-common.md` |
| api-core API | `docs/api/api-core.md` |
| api-public-read API | `docs/api/api-public-read.md` |
| 이벤트 envelope / payload / idempotencyKey | `docs/event/event-envelope.md` |
| async-worker 처리 흐름 / retry / DLQ | `docs/async/async-worker.md` |
| monitoring metric / log | `docs/monitoring/monitoring.md` |
| RDS schema | `docs/data/db-schema.md` |
| Redis key / TTL | `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md` |
| external-ingestion | `docs/ingestion/external-ingestion.md` |
| Git workflow | `docs/git-workflow.md` |

문서와 구현이 충돌하면 코드 수정 전에 문서를 먼저 정리한다.

---

## 7. 문서 동시 수정 규칙

- API 계약이 바뀌면 해당 API 문서를 함께 수정한다.
- 공통 응답, error code, enum, validation, 인증 정책이 바뀌면 `docs/api/api-common.md`를 수정한다.
- eventType, eventId, traceId, idempotencyKey, payload가 바뀌면 `docs/event/event-envelope.md`를 수정한다.
- worker routing, Redis SET/DEL, retry, DLQ, idempotency 소비 로직이 바뀌면 `docs/async/async-worker.md`를 수정한다.
- metric 이름, label, structured log field, dashboard, alert 기준이 바뀌면 `docs/monitoring/monitoring.md`를 수정한다.
- Redis key naming 또는 TTL이 바뀌면 Redis 문서와 해당 key를 참조하는 event/worker 문서를 함께 수정한다.
- 문서 간 내용이 충돌하면 더 구체적인 책임 문서를 우선하되, 공통 정책과 충돌하는 경우 공통 문서를 먼저 수정한다.

---

## 8. Worktree 전략

| 경로 | Branch | 담당 |
| --- | --- | --- |
| `safespot-worktrees/api-core` | `worktree/api-core` | api-core |
| `safespot-worktrees/api-public-read` | `worktree/api-public-read` | api-public-read |
| `safespot-worktrees/external-ingestion` | `worktree/external-ingestion` | external-ingestion |
| `safespot-worktrees/async-worker` | `worktree/async-worker` | async-worker |
| `safespot-worktrees/review` | `review/*` | 리뷰 |

원칙:

- 모든 worktree branch는 origin/main 기준으로 관리한다.
- 서비스 경계 외 수정은 피한다.
- cross-service 변경은 PR에서 명시한다.
- review worktree는 diff 검토와 피드백 중심으로 사용한다.

---

## 9. Git Workflow

- main 직접 push 금지
- PR + 리뷰 + CI 통과 후 merge
- force-push 금지
- history rewrite 금지
- unrelated file 변경 금지
- 최소 diff 유지

---

## 10. 금지사항

### 10.1 서비스 경계 위반

- 다른 서비스 디렉터리 직접 수정 금지
- 공통 변경은 문서를 먼저 수정한 뒤 코드에 반영
- 다른 서비스의 repository/service를 직접 import 금지

### 10.2 데이터 원칙 위반

- Redis에 원본 데이터 저장 금지
- RDS bypass 금지
- 캐시 없이 DB 반복 조회하는 read path 구현 금지

### 10.3 비동기 오용

- read path에 SQS 사용 금지
- 동기 응답을 async 처리로 대체 금지
- worker에서 외부 API 직접 호출 금지

### 10.4 문서 무시

- spec과 다른 구현 금지
- 암묵적 계약 변경 금지
- event/idempotency 변경 시 문서 미수정 금지

---

## 11. 변경 프로세스

구조 또는 정책 변경 시 다음 순서를 따른다.

1. 관련 문서 수정
2. 팀 합의
3. 루트/서비스 `CLAUDE.md` 반영
4. 코드 반영
5. 테스트 및 리뷰

---

## 12. 핵심 설계 철학

- 서비스 경계 분리
- RDS 중심 데이터 일관성
- Redis 기반 조회 최적화
- 비동기 후처리 분리
- 문서 기반 개발
