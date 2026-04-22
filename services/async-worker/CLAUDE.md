# async-worker Service Guide

## 1. 책임

`async-worker`는 SQS/Lambda 기반 이벤트 소비와 Redis 캐시 갱신, Read Model 재구성을 담당한다.

포함:

- SQS message consumption
- Lambda worker execution
- 공통 Envelope 기반 event parsing
- eventType별 handler dispatch
- idempotency 검증 및 중복 처리
- Redis SET/DEL 기반 cache refresh 및 read model rebuild
- changedFields 기반 조건부 Redis 갱신
- RDS COUNT() 기반 shelter 현재인원 계산
- retry / DLQ 처리
- `ReportBatchItemFailures` 기반 partial batch failure 처리
- worker-level metric/log
- SQS/Lambda 운영 metric 확인

제외:

- 공개 조회 API
- 관리자 write API
- API 응답 경로 개입
- 외부 공공 API 직접 호출
- RDS INSERT / UPDATE 트랜잭션 처리
- api-core 또는 external-ingestion의 이벤트 발행
- Redis 인프라 생성
- CloudWatch/Grafana 인프라 구성

---

## 2. 절대 규칙

- API controller를 만들지 않는다.
- async-worker는 인바운드 HTTP 요청을 처리하지 않는다.
- 외부 공공 API를 직접 호출하지 않는다.
- worker는 RDS에 이미 저장된 결과만 읽는다.
- read path에 SQS 등 비동기 큐를 삽입하지 않는다.
- SQS는 at-least-once delivery임을 전제로 한다.
- idempotency를 무시하지 않는다.
- payload 계약을 임의로 변경하지 않는다.
- invalid payload는 성공 처리하지 않는다.
- 로그에 payload 전체를 덤프하지 않는다.

---

## 3. 우선 확인 문서

동작을 변경하기 전 관련 문서를 먼저 확인한다.

- 이벤트 envelope / payload / idempotencyKey: `docs/event/event-envelope.md`
- async-worker 처리 흐름 / retry / DLQ: `docs/async/async-worker.md`
- monitoring metric/log: `docs/monitoring/monitoring.md`
- Redis key / TTL: `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md`
- api-public-read cache regeneration 기준: `docs/api/api-public-read.md`
- RDS schema: `docs/data/db-schema.md`

---

## 4. Worker 처리 흐름

기본 흐름:

```text
SQS
-> Lambda
-> SqsBatchProcessor
-> EventEnvelope parser
-> idempotency check
-> EventDispatcher
-> Handler
-> Service
-> Redis SET/DEL
-> ACK or retry/DLQ
```

처리 단계:

1. SQS 메시지를 수신한다.
2. envelope를 역직렬화한다.
3. schema와 eventType을 검증한다.
4. idempotencyKey로 중복 여부를 확인한다.
5. eventType별 handler로 dispatch한다.
6. `changedFields` 등 조건부 skip 규칙을 적용한다.
7. 필요한 RDS 조회를 수행한다.
8. Redis SET/DEL을 수행한다.
9. metric/log를 남긴다.
10. 성공 시 ACK, 실패 시 partial batch failure로 재시도/DLQ 흐름을 따른다.

---

## 5. 컴포넌트 분리

### cache-worker

담당 이벤트:

- `EvacuationEntryCreated`
- `EvacuationEntryExited`
- `EvacuationEntryUpdated`
- `ShelterUpdated`
- `EnvironmentDataCollected`
- `CacheRegenerationRequested` 중 shelter/environment 계열

담당 작업:

- `shelter:status:{shelterId}` 재계산 후 SET
- `env:weather:{nx}:{ny}` SET
- `env:air:{station_name}` SET
- shelter 계열 cache regeneration 처리

### readmodel-worker

담당 이벤트:

- `DisasterDataCollected`
- `CacheRegenerationRequested` 중 disaster 계열

담당 작업:

- `disaster:active:{region}` SET/DEL
- `disaster:alert:list:{region}:{disasterType}` SET
- `disaster:detail:{alertId}` SET
- `disaster:latest:{disasterType}:{region}` SET

---

## 6. Event Envelope 규칙

공통 envelope 필드:

- `eventId`
- `eventType`
- `occurredAt`
- `producer`
- `traceId`
- `idempotencyKey`
- `payload`

eventType과 payload는 `docs/event/event-envelope.md`를 기준으로 한다.

---

## 7. Idempotency 규칙

- SQS 메시지는 중복 수신될 수 있다.
- idempotencyKey는 중복 소비 방지를 위한 기준이다.
- idempotencyKey 검증이 정상적인 후속 이벤트를 막으면 안 된다.
- 처리 완료한 idempotencyKey만 dedup 저장한다.
- 실패한 메시지는 재시도될 수 있어야 한다.

idempotencyKey 기준:

- `EvacuationEntryCreated`: `entry:{entryId}:ENTERED:v{version}`
- `EvacuationEntryExited`: `entry:{entryId}:EXITED:v{version}`
- `EvacuationEntryUpdated`: `entry:{entryId}:UPDATED:{eventId}`
- `ShelterUpdated`: `shelter:{shelterId}:UPDATED:{eventId}`
- `CacheRegenerationRequested`: `cache-regen:{cacheKey}:{windowStart}`

주의:

- `EvacuationEntryUpdated`와 `ShelterUpdated`는 같은 대상에 대해 단시간 내 복수 이벤트가 정상적으로 발생할 수 있으므로 eventId 포함 key를 사용한다.
- Cache regeneration 이벤트는 suppress window 단위로 dedupe한다.
- Cache refresh 작업은 overwrite-safe하게 설계한다.

---

## 8. Redis 규칙

- Redis는 read-optimized derived data만 저장한다.
- 원본 데이터는 RDS에 있다.
- `evacuation_entry`, `evacuation_event_history`, `admin_audit_log`를 Redis source of truth로 저장하지 않는다.
- Redis key와 TTL은 문서 기준을 따른다.
- Redis key는 코드에서 하드코딩하지 말고 상수/helper를 사용한다.
- Redis write는 overwrite-safe하게 설계한다.
- Redis 실패 시 재시도 후 DLQ로 이동한다.
- 다음 조회 요청에서 Cache-Aside로 자연 복구될 수 있어야 한다.

TTL 기준:

- `shelter:status:{shelterId}`: 30초
- `disaster:active:{region}`: 2분
- `disaster:alert:list:{region}:{disasterType}`: 5분
- `disaster:detail:{alertId}`: 10분
- `env:weather:{nx}:{ny}`: 120분
- `env:air:{station_name}`: 120분

---

## 9. Retry / DLQ 규칙

- invalid schema는 재시도해도 성공하기 어려우므로 DLQ 이동 대상이다.
- 일시적 Redis/RDS 오류는 재시도 대상이다.
- maxReceiveCount 초과 시 DLQ로 이동한다.
- Lambda는 partial batch failure를 사용해 실패 메시지만 재시도한다.
- DLQ 메시지는 `eventId`, `eventType`, `traceId`, `idempotencyKey`, `errorMessage`, `retryCount`를 확인할 수 있어야 한다.

---

## 10. Monitoring 규칙

Worker custom metric:

- `worker_processed_total`
- `worker_success_total`
- `worker_failures_total`
- `worker_processing_duration_seconds`
- `worker_idempotency_skipped_total`
- `worker_redis_write_total`
- `worker_batch_size`
- `worker_partial_batch_failure_total`
- `worker_dlq_publish_total`

SQS/Lambda metric은 CloudWatch 기준으로 확인한다.

- SQS visible messages
- SQS not visible messages
- SQS oldest message age
- DLQ visible messages
- Lambda invocations
- Lambda errors
- Lambda throttles
- Lambda duration
- Lambda concurrent executions

metric label은 `event_type`, `queue_name`, `result`, `reason` 중심으로 둔다. `messageId`, `eventId`, `shelterId` 같은 high-cardinality 값은 metric label에 넣지 않는다.

---

## 11. Structured Log 규칙

성공/실패 로그에는 다음 필드를 포함한다.

- `service`
- `event`
- `traceId`
- `awsRequestId`
- `messageId`
- `queueName`
- `eventId`
- `eventType`
- `idempotencyKey`
- `result`
- `reason`
- `durationMs`

Redis 작업 로그에는 필요한 경우 다음 필드를 포함한다.

- `operation`
- `key`
- `ttlSeconds`
- `result`

단, 개인정보가 포함될 수 있는 payload 전체를 로그로 남기지 않는다.

---

## 12. 수정 가능 범위

주 수정 대상:

- `src/main/java/.../consumer`
- `src/main/java/.../handler`
- `src/main/java/.../service`
- `src/main/java/.../idempotency`
- `src/main/java/.../envelope`
- `src/main/java/.../payload`
- `src/main/java/.../redis`
- `src/main/java/.../metrics`
- `src/main/resources`

관련 변경 시 함께 확인:

- `docs/event/event-envelope.md`
- `docs/async/async-worker.md`
- `docs/monitoring/monitoring.md`
- `docs/redis-key/redis-key.md`
- `docs/redis-key/cache-ttl.md`

수정 금지:

- `services/api-core/**`
- `services/api-public-read/**`
- `services/external-ingestion/**`

---

## 13. 코드 작성 원칙

- envelope parsing과 business handling을 분리한다.
- handler는 eventType 중심으로 나눈다.
- RDS COUNT() 로직과 Redis SET 로직을 과도하게 결합하지 않는다.
- Redis key 형식은 상수/helper를 통해 생성한다.
- Lambda Java는 필요 시 SnapStart(Java 21) 활성화를 고려한다.
- 실패는 숨기지 말고 metric/log와 retry/DLQ 흐름으로 드러낸다.

---

## 14. 금지 패턴

- REST endpoint 생성
- 관리자 감사 로그 직접 생성
- 외부 공공 API 수집 로직 추가
- read path에 SQS 삽입
- payload 계약 검증 없이 처리 계속
- cache-worker/readmodel-worker 로직을 다른 서비스에 공유
- 개인정보 포함 payload 전체 로그 출력
