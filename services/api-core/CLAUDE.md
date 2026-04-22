# api-core Service Guide

## 1. 책임

`api-core`는 SafeSpot의 인증, 관리자 보호 워크로드, 관리자 write API를 담당한다.

포함:

- 로그인
- 내 정보 조회
- 관리자 dashboard 조회
- 관리자 입소 등록 / 퇴소 / 수정
- shelter 운영정보 수정
- 동기 RDS 트랜잭션 처리
- 관리자 감사 로그 기록
- DB commit 이후 도메인 이벤트 발행
- 즉시 stale 제거가 필요한 Redis 캐시 무효화(DEL)
- api-core application metric/log

제외:

- 공개 조회 API
- 외부 API 수집
- Redis SET / read model 재계산
- async consumer
- cache refresh worker
- worker retry / DLQ 처리

---

## 2. 절대 규칙

- write 성공 전에 이벤트를 발행하지 않는다.
- 이벤트는 DB commit 이후 발행한다.
- 개인정보를 로그에 기록하지 않는다.
- 이벤트 payload에 개인정보 값을 넣지 않는다.
- 공개 조회용 read model을 여기서 직접 재구성하지 않는다.
- Redis SET을 수행하지 않는다.
- external-ingestion 로직을 넣지 않는다.
- worker용 polling / consumer 로직을 넣지 않는다.
- 다른 서비스 코드를 직접 import하지 않는다.

---

## 3. 우선 확인 문서

동작을 변경하기 전 관련 문서를 먼저 확인한다.

- API 공통 정책: `docs/api/api-common.md`
- api-core API: `docs/api/api-core.md`
- 이벤트 envelope / payload / idempotencyKey: `docs/event/event-envelope.md`
- worker 기대 동작: `docs/async/async-worker.md`
- monitoring metric/log: `docs/monitoring/monitoring.md`
- RDS schema: `docs/data/db-schema.md`
- Redis key / TTL: `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md`

---

## 4. 구현 방향

### 인증

- JWT Access Token only
- Refresh Token 미도입
- role 기반 인가
- JWT에는 민감 개인정보를 넣지 않는다.

### 관리자 write

- 트랜잭션 경계를 명확히 유지한다.
- 상태 변경과 감사 로그를 같은 동기 처리 흐름에서 남긴다.
- `reason` 필드 정책은 `docs/api/api-core.md`를 따른다.
- API error code는 `docs/api/api-common.md`와 일치시킨다.

### 이벤트

- 이벤트 envelope 스펙은 `docs/event/event-envelope.md`를 기준으로 한다.
- 각 서비스는 자체 DTO를 유지하되, 계약 기준은 docs이다.
- 공통 envelope에는 `eventId`, `eventType`, `occurredAt`, `producer`, `traceId`, `idempotencyKey`, `payload`를 포함한다.
- `changedFields`에는 변경된 필드명만 넣는다.
- 개인정보 값 자체를 payload에 넣지 않는다.

idempotencyKey 규칙:

- `EvacuationEntryCreated`: `entry:{entryId}:ENTERED:v{version}`
- `EvacuationEntryExited`: `entry:{entryId}:EXITED:v{version}`
- `EvacuationEntryUpdated`: `entry:{entryId}:UPDATED:{eventId}`
- `ShelterUpdated`: `shelter:{shelterId}:UPDATED:{eventId}`

`EvacuationEntryUpdated`, `ShelterUpdated`는 같은 대상에 대해 단시간 내 정상적인 복수 이벤트가 발생할 수 있으므로 `eventId`를 idempotencyKey에 포함한다.

### Redis

- api-core는 즉시 stale 제거가 필요한 도메인 캐시 키를 DEL할 수 있다.
- api-core는 Redis SET 또는 read model 재생성을 수행하지 않는다.
- Redis SET / read model rebuild는 async-worker 책임이다.

### Monitoring

- 관리자 API 호출 수, 관리자 action 성공/실패, 로그인 성공/실패, SQS publish 성공/실패/retry를 계측한다.
- metric 이름과 label은 `docs/monitoring/monitoring.md`를 따른다.
- high-cardinality 값은 metric label에 넣지 않고 structured log field로 남긴다.

---

## 5. 수정 가능 범위

주 수정 대상:

- `src/main/java/.../auth`
- `src/main/java/.../admin`
- `src/main/java/.../audit`
- `src/main/java/.../event`
- `src/main/java/.../metrics`
- `src/main/resources`

관련 변경 시 함께 확인:

- `docs/api/api-core.md`
- `docs/event/event-envelope.md`
- `docs/async/async-worker.md`
- `docs/monitoring/monitoring.md`

수정 금지:

- `services/api-public-read/**`
- `services/external-ingestion/**`
- `services/async-worker/**`

---

## 6. 코드 작성 원칙

- controller는 요청/응답 매핑만 담당한다.
- 비즈니스 규칙은 service에 둔다.
- repository는 persistence만 담당한다.
- event publish는 transaction commit 이후 흐름으로 분리한다.
- exception은 API 에러 코드와 일치시킨다.
- 개인정보 응답 노출 금지 규칙을 준수한다.
- 로그에 payload 전체를 덤프하지 않는다.

---

## 7. 금지 패턴

- 공통화를 이유로 domain 로직을 packages로 이동
- 다른 서비스 코드 직접 import
- read 전용 fallback 로직 구현
- ingestion 스케줄링 추가
- Redis consumer 추가
- Redis SET 기반 cache rebuild 구현
