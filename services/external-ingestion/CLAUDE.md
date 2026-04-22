# external-ingestion Service Guide

## 1. 책임

`external-ingestion`은 외부 API 수집과 정규화를 담당한다.

포함:

- source별 polling loop
- Kubernetes CronJob 기반 수집 실행 로직
- 외부 API 호출
- raw payload 저장
- payload hash 기반 중복 감지
- 데이터 정규화
- RDS 적재
- 실행 이력 기록
- 정규화 오류 이력 기록
- 정규화 완료 후 cache refresh 이벤트 발행
- ingestion metric/log

제외:

- 공개 조회 API
- 관리자 write API
- Redis 직접 갱신
- 공개 응답 조립
- API 인증 발급
- async-worker retry / DLQ 처리
- read model rebuild 실행

---

## 2. 절대 규칙

- Redis를 직접 갱신하지 않는다.
- RDS 적재 완료 전 cache refresh 이벤트를 발행하지 않는다.
- raw payload 저장과 normalized write를 혼동하지 않는다.
- 외부 API별 polling 주기를 임의 변경하지 않는다.
- source / schedule / execution log / raw payload / normalization error 책임을 섞지 않는다.
- worker가 해야 할 Redis refresh 로직을 넣지 않는다.
- 다른 서비스 repository를 직접 참조하지 않는다.

---

## 3. 우선 확인 문서

동작을 변경하기 전 관련 문서를 먼저 확인한다.

- external-ingestion 설계: `docs/ingestion/external-ingestion.md`
- 이벤트 envelope / payload / idempotencyKey: `docs/event/event-envelope.md`
- worker 기대 동작: `docs/async/async-worker.md`
- monitoring metric/log: `docs/monitoring/monitoring.md`
- RDS schema: `docs/data/db-schema.md`
- Redis key / TTL: `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md`

---

## 4. 구현 방향

### 수집

- source별 polling 방식 또는 CronJob 방식을 문서 기준으로 구현한다.
- 호출 제한이 있는 API는 rate limit을 준수한다.
- 네트워크 오류는 제한된 재시도 후 실패 이력으로 기록한다.
- 외부 API 응답은 raw payload로 먼저 보존한다.

### 저장

- 원본 응답은 raw payload 테이블에 저장한다.
- 정규화 결과는 대상 도메인 테이블에 저장한다.
- 원본과 정규화 결과를 한 테이블에 섞지 않는다.
- payload hash로 중복 수집 여부를 확인한다.

### 정규화

- source별 client와 normalizer를 분리한다.
- source별 필드 변환 책임을 분리한다.
- 검증 실패는 normalization error에 기록한다.
- partial success 가능성을 반영한다.
- shelter 데이터는 selective upsert 정책을 따른다.

### 이벤트

- Redis refresh는 직접 수행하지 않고 이벤트만 발행한다.
- cache refresh 이벤트는 정규화 결과가 RDS에 저장된 뒤 발행한다.
- event envelope는 `docs/event/event-envelope.md`를 따른다.
- emitted event를 변경하면 `docs/event/event-envelope.md`와 `docs/async/async-worker.md`를 함께 확인한다.

---

## 5. IdempotencyKey 기준

external-ingestion의 cache refresh 이벤트는 대부분 overwrite-safe 작업이다. 따라서 deterministic idempotencyKey를 사용할 수 있다.

예시:

- `alert:{alertId}:CACHE_REFRESH`
- `env:weather:{nx}:{ny}:CACHE_REFRESH`
- `env:air:{stationName}:CACHE_REFRESH`

단, 특정 수집 time window별 이벤트를 반드시 각각 처리해야 하는 경우에는 다음 중 하나를 idempotencyKey에 포함한다.

- `forecastedAt`
- `measuredAt`
- `completedAt`
- `executionId`
- `timeWindow`

무조건 `eventId`를 붙이기보다, worker 처리 의미에 맞는 domain time key를 우선 검토한다.

---

## 6. Monitoring 규칙

다음 metric을 source 단위로 계측한다.

- `ingestion_polling_loop_iteration_total`
- `ingestion_polling_loop_skipped_total`
- `ingestion_external_api_call_total`
- `ingestion_external_api_failure_total`
- `ingestion_external_api_retry_total`
- `ingestion_external_api_latency_seconds`
- `ingestion_external_api_rate_limit_exceeded_total`
- `ingestion_total_fetch_duration_seconds`
- `ingestion_last_success_timestamp`
- `ingestion_records_fetched_total`
- `ingestion_records_normalized_total`
- `ingestion_records_failed_total`
- `ingestion_duplicate_payload_total`
- `ingestion_normalization_duration_seconds`
- `ingestion_normalization_failure_total`
- `ingestion_sqs_publish_total`
- `ingestion_sqs_publish_failure_total`

metric label은 `source`, `type`, `reason`, `queue_name`, `event_type` 중심으로 둔다. `rawId`, `executionId`, `payloadHash` 같은 high-cardinality 값은 metric label에 넣지 않고 log field로 남긴다.

---

## 7. Structured Log 규칙

수집 성공 로그에는 다음 필드를 포함한다.

- `service`
- `event`
- `traceId`
- `source`
- `executionId`
- `rawId`
- `payloadHash`
- `recordsFetched`
- `recordsNormalized`
- `recordsFailed`
- `httpStatus`

실패 로그에는 다음 필드를 포함한다.

- `errorCode`
- `errorType`
- `httpStatus`
- `retryCount`
- `message`

민감한 API key, authorization token, service key는 로그에 남기지 않는다.

---

## 8. 수정 가능 범위

주 수정 대상:

- `src/main/java/.../client`
- `src/main/java/.../config`
- `src/main/java/.../handler`
- `src/main/java/.../normalizer`
- `src/main/java/.../publisher`
- `src/main/java/.../queue`
- `src/main/java/.../scheduler`
- `src/main/java/.../service`
- `src/main/java/.../metrics`
- `src/main/resources`

관련 변경 시 함께 확인:

- `docs/ingestion/external-ingestion.md`
- `docs/event/event-envelope.md`
- `docs/async/async-worker.md`
- `docs/monitoring/monitoring.md`

수정 금지:

- `services/api-core/**`
- `services/api-public-read/**`
- `services/async-worker/**`

---

## 9. 코드 작성 원칙

- source별 client와 normalizer를 분리한다.
- 테이블 책임을 서비스 클래스 하나에 몰아넣지 않는다.
- raw payload 저장 후 normalize queue publish 순서를 지킨다.
- normalize 후 RDS 적재 완료 뒤 refresh event를 발행한다.
- source별 장애 우선순위를 구분한다.
- 외부 API 호출 제한과 retry 정책을 코드에 명시적으로 반영한다.
- 실패는 execution log와 normalization error에 남긴다.

---

## 10. 금지 패턴

- Redis 직접 SET
- Redis 직접 DEL
- 공개 조회용 endpoint 구현
- 관리자 수정 endpoint 구현
- packages에 ingestion 구현체 공유
- 다른 서비스 repository 직접 참조
- worker retry / DLQ 로직 구현
- API key 원문 로그 출력
