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
├── packages/
│   ├── event-schema/
│   ├── common-redis-keys/
│   └── observability-contract/
├── docs/
├── deploy/
└── CLAUDE.md
```

### 구조 원칙

- `services/` → 실행 단위 (deployable workload)
- `packages/` → 서비스 간 공유 계약 (단일 서비스 종속 금지)
- `docs/` → Source of Truth
- `deploy/` → 실행/환경/스크립트

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

## 7. packages 사용 규칙

### 7.1 event-schema

- 이벤트 계약 정의
- 모든 서비스 동일 스키마 사용

---

### 7.2 common-redis-keys

- Redis key naming 표준화
- 하드코딩 금지

---

### 7.3 observability-contract

- metrics / logging / tracing 규약 정의

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
- 공통 변경은 packages 또는 docs 통해 반영

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