# SafeSpot Application Repository Guide

## 1. 목적

이 저장소는 SafeSpot 재난 대피 서비스 애플리케이션 monorepo이다.

본 문서는 **루트 정책 문서 (Global Policy)** 이며:

- 저장소 구조
- 서비스 경계
- 공통 규칙
- Git / Worktree 운영 방식
- 문서 Source of Truth

를 정의한다.

> 구현 상세 / 도메인 로직 / 코드 규칙은  
> 각 서비스 디렉터리의 `CLAUDE.md`가 우선한다.

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
└── CLAUDE.md
```

### 구조 원칙

- `services/` → 실행 단위 (deployable workload)
- `docs/` → Source of Truth
- `deploy/` → 실행/환경/스크립트

> **packages 모듈 비활성화 결정 (2026-04-22)**
> packages/* 모듈(event-schema, common-redis-keys, observability-contract)은 현재 사용하지 않는다.
> 각 서비스가 자체 source of truth를 유지한다. async-worker의 계약(Envelope, Payload, RedisKey)은
> `services/async-worker` 내부가 유일한 source of truth다.
> 다른 서비스와 계약 공유가 필요해지면 packages로 promote한다.

---

## 3. 서비스 경계 (Critical)

### 3.1 api-core

- 인증
- 관리자 API
- write 트랜잭션
- 이벤트 발행
- Redis 캐시 무효화

---

### 3.2 api-public-read

- 공개 조회 API
- Redis 우선 조회
- RDS fallback 처리

---

### 3.3 external-ingestion

- 외부 API 수집
- 데이터 정규화
- RDS 적재
- 캐시 갱신 이벤트 발행

---

### 3.4 async-worker

- SQS 기반 이벤트 소비
- Redis 캐시 갱신
- 비동기 후처리

---

## 4. 데이터 원칙 (중요)

### 4.1 Source of Truth

- RDS = 공식 데이터
- Redis = 파생 데이터 (재생성 가능)

---

### 4.2 Read Path

```text
Client
→ api-public-read
→ Redis (HIT)
→ RDS fallback (MISS or 장애)
```

- Redis 장애는 코드 레벨 fallback으로 해결
- SQS는 read path에 사용하지 않는다

---

### 4.3 Write Path

```text
api-core
→ RDS commit (sync)
→ 이벤트 발행
→ async-worker
→ Redis 갱신
```

---

## 5. Worktree 전략 (필수)

### 5.1 구조

| 경로 | Branch | 담당 |
|------|--------|------|
| safespot-worktrees/api-core | worktree/api-core | api-core |
| safespot-worktrees/api-public-read | worktree/api-public-read | api-public-read |
| safespot-worktrees/external-ingestion | worktree/external-ingestion | external-ingestion |
| safespot-worktrees/async-worker | worktree/async-worker | async-worker |
| safespot-worktrees/review-m | review/codex-cross-review-m | 리뷰 |
| safespot-worktrees/review-s | review/codex-cross-review-s | 리뷰 |

---

### 5.2 원칙

- 모든 worktree branch는 origin/main 기준
- 서비스 경계 외 수정 금지
- cross-service 수정은 PR로만 수행

---

## 6. Git Workflow (강제 규칙)

### 6.1 main 브랜치

- 직접 push 금지
- PR + 리뷰 + CI 통과 후 merge

---

### 6.2 개발 흐름

```text
worktree branch
→ 구현
→ PR 생성
→ review worktree 검토
→ main merge
```

---

### 6.3 리뷰 역할

- review worktree는 코드 수정 금지
- diff 검토 + 피드백만 수행

---

## 7. 계약 소유권 (현재 상태)

현재 각 서비스가 자체 계약을 내부에 유지한다.

- **이벤트 Envelope / Payload DTO** → `services/async-worker/src/.../envelope`, `.../payload`
- **Redis Key 명명 규칙** → `services/async-worker/src/.../redis/RedisKeyConstants.java`
- **Redis TTL 정책** → `services/async-worker/src/.../redis/RedisTtlConstants.java`

> 여러 서비스가 동일 계약을 공유해야 할 시점에 `packages/` 모듈로 promote한다.
> 현재 단계에서는 조기 추상화를 피한다.

---

## 8. 문서 Source of Truth (절대 규칙)

다음 문서가 구현 기준이다:

- API spec → /docs/api/
- RDS schema → /docs/data/
- ingestion spec → /docs/ingestion/
- async_worker spec → /docs/async-worker/

충돌 발생 시:

> 코드 수정 금지 → 문서 먼저 수정

---

## 9. 금지사항 (강제)

### 9.1 서비스 경계 위반

- 다른 서비스 디렉터리 직접 수정 금지
- 공통 변경은 docs를 먼저 수정한 후 코드에 반영

---

### 9.2 데이터 원칙 위반

- Redis에 원본 데이터 저장 금지
- RDS bypass 금지
- 캐시 없이 DB 반복 조회 금지

---

### 9.3 비동기 오용

- Read path에 SQS 사용 금지
- 동기 응답을 async로 대체 금지

---

### 9.4 문서 무시

- spec과 다른 구현 금지
- 암묵적 변경 금지

---

## 10. 변경 프로세스

구조 또는 정책 변경 시:

1. docs 수정
2. 팀 합의
3. CLAUDE.md 반영
4. 코드 반영

---

## 11. 핵심 설계 철학

- 서비스 경계 분리
- RDS 중심 데이터 일관성
- Redis 기반 조회 최적화
- 비동기 처리 분리
- 문서 기반 개발