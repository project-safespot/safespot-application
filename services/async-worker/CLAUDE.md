# async+worker Service Guide

## 1. 책임

이 서비스는 SQS 이벤트 소비와 Redis 캐시 갱신·Read Model 재구성을 담당한다.

포함:
- SQS consumer (evacuation-events, disaster-events, environment-events)
- 공통 Envelope 기반 event parsing
- eventType별 handler 분기 (cache-worker / readmodel-worker 분리)
- idempotent 처리 (이벤트별 no-op / overwrite 기준 적용)
- Redis key SET 및 readmodel-worker의 disaster:active:{region} 재생성 처리 (필요 시 DEL 후 SET 방식으로 일관성 유지)
- changedFields 기반 조건부 Redis 갱신 (EvacuationEntryUpdated, ShelterUpdated)
- RDS COUNT() 기반 현재인원 계산 (cache-worker 전담)
- retry / DLQ 대응 (SQS maxReceiveCount + ReportBatchItemFailures)
- 소비자 관점 observability (traceId / eventId / idempotencyKey 중심 로깅)

제외:
- 공개 조회 API (api-public-read 담당)
- 관리자 write API (api-core 담당)
- 외부 공공 API 직접 호출 (external-ingestion Normalizer 담당)
- shelter:status:{id}, shelter:list:{type}:{disasterType} DEL (api-core 담당)
- RDS INSERT / UPDATE / 트랜잭션 처리 (api-core 담당)
- SQS 이벤트 발행 (api-core, external-ingestion Normalizer 담당)
- Redis 인프라 생성 및 보안 그룹 (data 영역 담당)
- Scheduler 기반 워크로드 (compute 영역 담당)
- CloudWatch 대시보드·모니터링 인프라 구성 (ops 담당)
- Read path(조회 경로) 개입 (절대 금지)

---

## 2. 절대 규칙

- API controller를 만들지 않는다. async+worker는 인바운드 HTTP 요청을 처리하지 않는다.
- 외부 공공 API를 직접 호출하지 않는다. worker는 RDS에 이미 저장된 결과만 읽는다.
- shelter:status:{id} DEL을 worker에서 수행하지 않는다. DEL은 api-core 전담이다.
- Read path(조회 경로)에 SQS 등 비동기 큐를 삽입하지 않는다. 절대 금지.
- async+worker는 Scheduler / polling 없이 SQS event consumer로만 동작한다.
- idempotency를 무시하지 않는다. at-least-once 전제 하에 중복 수신 가능성을 항상 고려한다.
- payload 계약(공통 Envelope, eventType별 스펙)을 임의로 변경하지 않는다.
- readmodel-worker는 disaster:active:{region}를 필요 시 재생성하며, DEL 후 SET 방식으로 일관성을 유지한다.

---

## 3. 구현 방향

### 컴포넌트 분리

- **cache-worker**: `evacuation-events`, `environment-events` 큐 소비
  - `EvacuationEntryCreated`, `EvacuationEntryExited`, `EvacuationEntryUpdated`, `ShelterUpdated` → RDS COUNT() 후 `shelter:status:{shelterId}` SET
  - `EnvironmentDataCollected` → `env:weather:{nx}:{ny}` 또는 `env:air:{station_name}` SET
- **readmodel-worker**: `disaster-events` 큐 소비
  - `DisasterDataCollected` → `disaster:active:{region}`, `disaster:alert:list:{region}:{disasterType}`, `disaster:detail:{alertId}` SET

### 이벤트 소비

- 공통 Envelope 스키마 사용 (`eventId`, `eventType`, `occurredAt`, `producer`, `traceId`, `idempotencyKey`, `payload`)
- eventType별 handler 분리
- invalid payload는 처리 실패로 간주되며, SQS 재시도 정책 이후 DLQ로 이동한다
- `SQSBatchResponse.BatchItemFailures` 사용 — 배치 내 개별 메시지 실패만 재시도

### Redis 갱신

- key naming은 shared contract 기준 준수
  - `shelter:status:{shelterId}` / `env:weather:{nx}:{ny}` / `env:air:{station_name}`
  - `disaster:active:{region}` / `disaster:alert:list:{region}:{disasterType}` / `disaster:detail:{alertId}`
- TTL 정책 준수: shelter 30초 / disaster:active 2분 / disaster:alert:list 5분 / disaster:detail 10분 / env 120분
- `EvacuationEntryUpdated`: `changedFields`가 현재인원에 영향 없으면 Redis 갱신 없이 no-op
- `ShelterUpdated`: `changedFields`에 `capacityTotal` 또는 `shelterStatus` 포함 시에만 재계산
- Redis 실패 시 재시도 후 DLQ 이동. 다음 조회 요청에서 Cache-Aside로 자연 복구

### idempotency

- 중복 소비 가능성을 전제로 구현 (SQS at-least-once)
- 이벤트별 멱등 처리 기준:
  - `EvacuationEntryCreated`: `entryId + nextStatus` 동일 시 no-op
  - `EvacuationEntryExited`: `entryId`가 이미 EXITED면 no-op
  - `EvacuationEntryUpdated`: 동일 변경 반복 시 no-op
  - `ShelterUpdated`: 이전 상태와 동일하면 no-op, 다른 상태면 최종값 overwrite
  - `DisasterDataCollected`: `source + region + issuedAt` 기준 dedupe
  - `EnvironmentDataCollected`: `region + collectionType + timeWindow` 기준 overwrite

---

## 4. 수정 가능 범위

주 수정 대상 (Terraform):
- `modules/messaging/sqs/**`              ← SQS / DLQ 3종
- `modules/application/lambda-worker/**`  ← Lambda 함수, IAM role, event source mapping

이벤트 계약 (이 서비스가 단일 Source of Truth):
- `src/main/java/.../envelope`       ← 공통 Envelope DTO (EventEnvelope, EventType)
- `src/main/java/.../payload`        ← eventType별 payload DTO
- `src/main/java/.../redis/RedisKeyConstants.java` ← Redis key 명명 규칙
- `src/main/java/.../redis/RedisTtlConstants.java` ← Redis TTL 정책

주 수정 대상 (애플리케이션):
- `src/main/java/.../consumer`       ← SQS 메시지 수신 / BatchItemFailures 처리
- `src/main/java/.../handler`        ← eventType별 분기 처리
- `src/main/java/.../service`        ← RDS COUNT() 로직, Redis SET/DEL 로직
- `src/main/java/.../idempotency`    ← 멱등키 검증 로직
- `src/main/resources`               ← application.yml (Redis/RDS 연결, 환경변수)

수정 금지:
- `services/api-core/**`
- `services/api-public-read/**`
- `services/external-ingestion/**`

---

## 5. 코드 작성 원칙

- event parsing(Envelope 역직렬화)과 business handling(handler 분기)을 분리한다
- RDS COUNT() 로직과 Redis SET 로직을 같은 메서드에 묶지 않는다
- handler는 eventType 중심으로 나누되, cache-worker / readmodel-worker 간 코드를 공유하지 않는다
- Redis key 형식은 `RedisKeyConstants.java`를 단일 출처로 사용한다. 하드코딩 금지
- 로그에 payload 전체를 덤프하지 않는다. 개인정보가 포함될 수 있음
- 추적 필드는 `traceId`, `eventId`, `idempotencyKey` 중심으로 남긴다
- Lambda Java는 SnapStart(Java 21) 활성화로 Cold Start를 보완한다

---

## 6. 우선 확인 문서

- 비동기 처리 정책 정의서 — 이벤트 envelope 명세, 이벤트별 payload 스펙, TTL 정책, 재시도·DLQ 정책
- asyncworker 영역 확정 계약서 — 포함/제외 책임 범위, 핵심 설계 결정 근거
- asyncworker 모듈 인터페이스 정의서 — INPUT/OUTPUT 변수, RESOURCE 목록, 포트 매핑
- Terraform 협업 규칙 — 모듈 apply 순서 (network → data → async+worker 순서 준수)

---

## 7. 금지 패턴

- REST endpoint 생성
- 관리자 감사 로그(`admin_audit_log`) 직접 생성
- 외부 공공 API 수집 로직 추가
- `shelter:status:{id}` DEL을 worker에서 수행
- Read path에 SQS 큐 삽입
- cache-worker / readmodel-worker 구현을 외부 모듈에 공유
- payload 계약 검증 없이 처리 계속 (잘못된 이벤트는 반드시 EPE/DLQ로)