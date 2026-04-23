# SafeSpot Handoff — 2026-04-24

Claude Code → Codex 인수인계 문서.

---

## 저장소 개요

```
safespot-application/          ← main repo (main 브랜치)
safespot-worktrees/
  api-core/                    ← worktree/api-core
  api-public-read/             ← worktree/api-public-read
  external-ingestion/          ← worktree/external-ingestion
  review/                      ← review/* (PR 검토용)
```

docs Source of Truth: `docs/README.md`

---

## 현재 상태

### main 브랜치 (병합 완료)

| 서비스 | 상태 |
|---|---|
| async-worker | 병합 완료 (PR #9, #13) |
| api-core | 병합 완료 (PR #11) |

### 열린 PR

| PR | 서비스 | 상태 |
|---|---|---|
| #10 | api-public-read | 리뷰 코멘트 있음, 미병합 |
| #12 | external-ingestion | 리뷰 코멘트 있음, 미병합 |

---

## 서비스별 구현 완료 범위

### api-core (병합됨)
- JWT 로그인, 내 정보 조회
- 관리자 API: 대시보드, 입소/퇴소/수정, 대피소 정보 수정
- 감사 로그 (`admin_audit_log`)
- DB commit 이후 도메인 이벤트 SQS 발행 (`@TransactionalEventListener(AFTER_COMMIT)`)
- 즉시 stale 제거 Redis DEL (shelter detail, shelter list)
- SQS 재시도: 최대 3회, 지수 백오프 (1s/3s/9s), daemon 스레드
- Micrometer 계측: 로그인, SQS publish/retry, shelter 혼잡도 게이지
- 테스트 23개 통과

### async-worker (병합됨)
- SQS 소비 → eventType별 handler dispatch
- Idempotency 검증 (Redis `SETNX`)
- Redis SET 기반 cache-worker (shelter detail/list, disaster, 날씨, 대기)
- DLQ/retry 처리

### api-public-read (PR #10, 미병합)
- 6개 공개 조회 엔드포인트
- Redis-first → RDS fallback → cache regeneration 이벤트 발행
- suppress window (10초, instance-local, `ConcurrentHashMap.compute()` atomic)
- 재난 최신 조회: 2-step pointer key (`disaster:latest:{type}:{region}` → `disaster:detail:{alertId}`)
- 날씨: grid (`?nx&ny`) + region (`?region`) 두 경로 모두 지원
- 리뷰 코멘트 반영 완료, 최종 push 상태 (토큰 소진으로 병합 보류)

### external-ingestion (PR #12, 미병합)
- 외부 API 10종 수집 파이프라인
- AbstractIngestionHandler → Normalizer → NormalizationService 레이어
- TX 분리: 외부 API 호출은 TX 외부, DB 적재는 단위 TX
- afterCommit 후 cache regeneration 이벤트 발행
- 민감 파라미터 마스킹 (serviceKey, apiKey, token 등)
- KMA_EARTHQUAKE 부분 적재 방지: detail 실패 시 alert 보상 삭제
- 테스트 23개

---

## 미완료 작업

### PR #10 / #12 병합
두 PR 모두 리뷰 코멘트가 있었고 수정 반영 완료 상태다. 리뷰어 승인 후 병합하면 된다.

### async-worker — idempotencyKey 계약 확인
main에 이미 병합된 async-worker가 아래 최신 idempotencyKey 포맷을 올바르게 파싱하는지 확인 필요:

| 이벤트 | idempotencyKey |
|---|---|
| EvacuationEntryCreated | `entry:{entryId}:ENTERED:v1` |
| EvacuationEntryExited | `entry:{entryId}:EXITED:v1` |
| EvacuationEntryUpdated | `entry:{entryId}:UPDATED:{eventId}` |
| ShelterUpdated | `shelter:{shelterId}:UPDATED:{eventId}` |

계약 기준: `docs/event/event-envelope.md`

### external-ingestion → SQS 실연동
현재 `LoggingCacheEventPublisher`(stub)만 있음. async-worker와 동일한 SQS endpoint를 바라보도록 실연동 필요. api-core의 `SqsEventPublisher` 구현 참고.

### end-to-end 통합 검증
서비스 4개가 전부 main에 올라간 뒤 로컬 docker-compose 환경(`deploy/`)에서 write → SQS → worker → Redis → read 전체 경로 검증.

---

## 핵심 설계 원칙 (변경 금지)

- **이벤트는 DB commit 이후 발행** (`@TransactionalEventListener(AFTER_COMMIT)`)
- **api-core는 Redis DEL만**, SET/rebuild는 async-worker 담당
- **Redis는 파생 데이터**, RDS가 Source of Truth
- **read path에 SQS 없음**, 동기 fallback 후 별도 이벤트로 재생성 요청

---

## 주요 파일 위치

| 목적 | 경로 |
|---|---|
| 이벤트 계약 | `docs/event/event-envelope.md` |
| API 계약 | `docs/api/api-core.md`, `docs/api/api-public-read.md` |
| 모니터링 계측 기준 | `docs/monitoring/monitoring.md` |
| Redis key/TTL | `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md` |
| 로컬 실행 | `deploy/` |
| Git 워크플로 | `docs/git-workflow.md` |
