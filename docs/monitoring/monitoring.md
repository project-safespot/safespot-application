# SafeSpot Monitoring 명세

이 문서는 SafeSpot의 Application 담당자와 Async-worker(SQS/Lambda) 담당자가 구현하고 운영해야 할 metric/log 수집 기준을 정의한다.

목표는 재난 상황의 spike traffic에서 다음을 빠르게 판단하는 것이다.

- API 응답이 유지되는가
- Redis cache가 DB를 보호하고 있는가
- SQS/Lambda worker가 밀리지 않고 처리하는가
- DB fallback이 급증하지 않는가
- DLQ로 데이터 유실 위험이 발생하지 않는가

## 1. Monitoring stack

| 대상 | 수집 방식 | 주 담당 |
| --- | --- | --- |
| Application metric | Micrometer -> `/actuator/prometheus` -> Prometheus | Application |
| Application log | stdout JSON log -> Fluent Bit -> CloudWatch Logs/OpenSearch/Loki | Application |
| Async-worker metric | Lambda CloudWatch Metrics + worker custom metric | Async-worker |
| Async-worker log | Lambda log -> CloudWatch Logs | Async-worker |
| SQS metric | CloudWatch Metrics | Async-worker |
| Redis infra metric | redis-exporter -> Prometheus | Redis Infra |
| Aurora PostgreSQL / RDS metric | CloudWatch / Performance Insights | DB/Infra |
| ALB/EKS metric | CloudWatch / Prometheus / Container Insights | EKS/Network |
| Dashboard / alert | Grafana + AlertManager, CloudWatch Alarm 병행 | 각 담당 |

공통 label:

```json
{
  "service": "api-core | api-public-read | async-worker | external-ingestion",
  "env": "dev",
  "region": "seoul"
}
```

추가 label은 metric 성격에 따라 `endpoint`, `event_type`, `source`, `queue_name`, `function_name`, `reason`, `result`를 사용한다.

사용자 ID, shelter ID, alert ID, SQS `messageId`처럼 cardinality가 큰 값은 metric label에 넣지 않고 log field로 남긴다.

## 2. Load test 기준

| 단계 | 목적 | 기준 |
| --- | --- | --- |
| Smoke | 기본 동작 확인 | 주요 API 정상 응답 |
| Load | 평상시 안정성 확인 | 동시 사용자 50~100명 |
| Stress | 한계와 병목 확인 | fallback, DB 부하, SQS backlog 확인 |
| Spike | 재난 직후 대응 확인 | 동시 사용자 5,000~10,000명, 5분 |
| Sustained disaster | 재난 지속 상황 확인 | 동시 사용자 1,000~3,000명 |

우선 관찰 API:

1. `GET /disaster-alerts`
2. 재난 overview / core message read path
3. `GET /shelters/nearby`
4. main 화면 API
5. 가족 조회 API

Spike test 성공 기준:

- API latency 유지
- Redis hit ratio 유지
- DB fallback 폭증 없음
- SQS backlog 증가 없음
- Lambda error/throttle 없음
- DLQ message 없음

## 3. Application 담당 범위

Application 담당자는 application code에서만 알 수 있는 business context와 Redis usage를 계측한다.

### 3.1 HTTP metric

Spring Boot Actuator 기본 metric인 `http.server.requests`를 표준 HTTP metric으로 사용한다. 별도 `http_requests_total`, `http_request_duration_seconds` custom metric은 중복 구현하지 않는다.

| 관점 | 수집 metric | label |
| --- | --- | --- |
| request count | `http.server.requests` count | `service`, `method`, `uri`, `status`, `outcome` |
| latency | `http.server.requests` max/sum/count 기반 p50/p95/p99 | `service`, `method`, `uri` |
| error rate | `http.server.requests` 중 `status=5xx`, `outcome=SERVER_ERROR` | `service`, `uri` |
| client error | `http.server.requests` 중 `status=4xx`, `outcome=CLIENT_ERROR` | `service`, `uri` |

Dashboard에서는 `/shelters/nearby`, `/disaster-alerts`, 재난 overview/core read path를 반드시 분리해서 본다.

### 3.2 api-public-read Redis usage metric

Redis 자체 상태는 redis-exporter가 수집한다. endpoint별 cache 사용 결과는 application code에서 Micrometer로 수집한다.

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `api_read_cache_request_total` | Counter | `endpoint`, `result` | Redis read 결과. `result=hit|miss|down|parse_error` |
| `api_read_cache_fallback_total` | Counter | `endpoint`, `reason` | degraded-mode DB fallback 발생. `reason=redis_miss|redis_down|parse_error` |
| `api_read_db_fallback_query_total` | Counter | `endpoint` | degraded-mode fallback 후 RDS 조회 발생 |
| `api_read_db_fallback_latency_seconds` | Timer | `endpoint` | degraded-mode fallback RDS query latency |
| `api_read_cache_regen_publish_total` | Counter | `endpoint`, `result` | `CacheRegenerationRequested` 발행 결과. `result=success|failure` |
| `api_read_cache_regen_requested_total` | Counter | `endpoint` | cache regeneration 요청 발생 |
| `api_read_cache_regen_suppressed_total` | Counter | `endpoint` | suppress window로 regeneration 요청 생략 |

재난 read model cache 관측에는 다음 `cache_key_family` 값을 공통 사용한다:

- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`
- `disaster_detail`

권장 metric:

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `cache_hit_total` | Counter | `service`, `cache_key_family` | Redis cache hit |
| `cache_miss_total` | Counter | `service`, `cache_key_family` | Redis cache miss |
| `cache_regeneration_requested_total` | Counter | `cache_key_family`, `event_type`, `reason`, `schema_version` | regeneration 요청 발생 |
| `cache_regeneration_completed_total` | Counter | `cache_key_family` | regeneration 완료 |
| `cache_regeneration_failed_total` | Counter | `cache_key_family`, `reason` | regeneration 실패 |
| `redis_hot_key_detected_total` | Counter | `cache_key_family` | hot key 감지 |
| `redis_payload_size_bytes` | Gauge or Summary | `cache_key_family` | payload size 관측 |

`cache_key_family` label은 `CacheRegenerationRequested.payload.cacheKeyFamily`에서 파생한다. `cacheKey`, `cacheKeyFamily`, `reason`, `schemaVersion`은 structured log field로 남긴다. `cacheKey`는 cardinality가 커질 수 있으므로 metric label로 사용하지 않는다.

필수 endpoint label 값은 route template을 사용한다.

| API | endpoint label |
| --- | --- |
| `GET /disaster-alerts` | `/disaster-alerts` |
| disaster overview recent read | `/disaster-overview/recent` |
| global 또는 menu core message read | `/global/disaster-core` |
| `GET /shelters/nearby` | `/shelters/nearby` |
| `GET /shelters/{shelterId}` | `/shelters/{shelterId}` |
| `GET /weather-alerts` | `/weather-alerts` |
| `GET /air-quality` | `/air-quality` |

Redis fallback log:

```json
{
  "level": "WARN",
  "service": "api-public-read",
  "event": "redis_fallback",
  "traceId": "uuid",
  "endpoint": "/shelters/nearby",
  "redisKey": "shelter:status:123",
  "reason": "redis_down",
  "fallback": "rds",
  "errorCode": "REDIS_CONNECTION_FAILURE"
}
```

metric에는 `redisKey`를 label로 넣지 않는다. Redis key는 log field로만 남긴다.

### 3.3 api-core metric

`api-core`는 관리자 write, 인증, domain event 발행을 담당한다.

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `api_core_admin_api_calls_total` | Counter | `method`, `endpoint`, `status` | 관리자 API 응답 수 |
| `api_core_admin_action_total` | Counter | `action` | 관리자 action 성공 |
| `api_core_admin_action_failed_total` | Counter | `action`, `reason` | 관리자 action 실패 |
| `api_core_auth_login_total` | Counter | `result`, `reason` | 로그인 성공/실패 |
| `api_core_shelter_checkin_total` | Counter | 없음 | 입소 처리 성공 |
| `api_core_shelter_checkout_total` | Counter | 없음 | 퇴소 처리 성공 |
| `api_core_shelter_full_count` | Gauge | 없음 | FULL 대피소 수 |
| `api_core_shelter_crowded_count` | Gauge | 없음 | CROWDED 대피소 수 |
| `api_core_shelter_open_count` | Gauge | 없음 | 운영 중 대피소 수 |
| `api_core_sqs_publish_total` | Counter | `event_type`, `result`, `queue_name` | SQS event 발행 결과 |
| `api_core_sqs_publish_retry_total` | Counter | `event_type`, `queue_name` | SQS publish retry |

> `api_core_shelter_full_count`는 `FULL` 상태를 운영 상태로만 집계한다.
> `FULL`은 요청 거절을 의미하지 않으며, capacity는 admission control이 아니다.

권장 action 값:

```text
ENTRY_CREATE
ENTRY_EXIT
ENTRY_UPDATE
SHELTER_UPDATE
```

권장 failure reason 값:

```text
validation_error
not_found
conflict
unauthorized
forbidden
db_error
sqs_publish_error
unknown
```

관리자 action log:

```json
{
  "level": "INFO",
  "service": "api-core",
  "event": "admin_action",
  "traceId": "uuid",
  "adminId": 10,
  "action": "ENTRY_CREATE",
  "targetType": "evacuation_entry",
  "targetId": 301,
  "result": "success"
}
```

SQS publish log:

```json
{
  "level": "INFO",
  "service": "api-core",
  "event": "sqs_publish",
  "traceId": "uuid",
  "eventId": "uuid",
  "eventType": "EvacuationEntryCreated",
  "idempotencyKey": "entry:301:ENTERED",
  "queueName": "safespot-cache-refresh",
  "result": "success"
}
```

### 3.4 external-ingestion metric

`external-ingestion`은 외부 API 수집, 원본 저장, 정규화, cache refresh event 발행 상태를 측정한다.

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `ingestion_polling_loop_iteration_total` | Counter | `source` | polling loop 실행 횟수 |
| `ingestion_polling_loop_skipped_total` | Counter | `source`, `reason` | disabled/rate_limit/error skip |
| `ingestion_external_api_call_total` | Counter | `source` | 외부 API 호출 |
| `ingestion_external_api_failure_total` | Counter | `source`, `type` | 외부 API 실패 |
| `ingestion_external_api_retry_total` | Counter | `source` | 외부 API retry |
| `ingestion_external_api_latency_seconds` | Timer | `source` | 외부 API latency |
| `ingestion_external_api_rate_limit_exceeded_total` | Counter | `source` | rate limit 초과 |
| `ingestion_total_fetch_duration_seconds` | Timer | `source` | 수집 전체 처리 시간 |
| `ingestion_last_success_timestamp` | Gauge | `source` | source별 마지막 성공 시각(epoch seconds) |
| `ingestion_records_fetched_total` | Counter | `source` | 수집 record 수 |
| `ingestion_records_normalized_total` | Counter | `source` | 정규화 성공 record 수 |
| `ingestion_records_failed_total` | Counter | `source` | 정규화 실패 record 수 |
| `ingestion_duplicate_payload_total` | Counter | `source` | 중복 payload skip |
| `ingestion_normalization_duration_seconds` | Timer | `source` | 정규화 처리 시간 |
| `ingestion_normalization_failure_total` | Counter | `source`, `reason` | 정규화 실패 |
| `ingestion_sqs_publish_total` | Counter | `source`, `queue_name`, `event_type` | cache refresh event 발행 성공 |
| `ingestion_sqs_publish_failure_total` | Counter | `source`, `queue_name`, `event_type` | cache refresh event 발행 실패 |

권장 `source` 값은 external API `source_code`를 그대로 사용한다.

```text
SAFETY_DATA_ALERT
KMA_EARTHQUAKE
SEOUL_EARTHQUAKE
FORESTRY_LANDSLIDE
SEOUL_RIVER_LEVEL
KMA_WEATHER
AIR_KOREA_AIR_QUALITY
SEOUL_SHELTER_EARTHQUAKE
SEOUL_SHELTER_LANDSLIDE
SEOUL_SHELTER_FLOOD
```

external-ingestion log:

```json
{
  "level": "INFO",
  "service": "external-ingestion",
  "event": "ingestion_completed",
  "traceId": "uuid",
  "source": "KMA_EARTHQUAKE",
  "executionId": 1001,
  "rawId": 9001,
  "payloadHash": "sha256",
  "recordsFetched": 1,
  "recordsNormalized": 1,
  "recordsFailed": 0,
  "httpStatus": 200
}
```

실패 로그는 `errorCode`, `errorType`, `httpStatus`, `retryCount`를 포함한다.

## 4. Async-worker(SQS/Lambda) 담당 범위

Async-worker 담당자는 SQS queue 상태, Lambda 처리 상태, worker business 처리 결과를 함께 책임진다.

Async-worker 처리 구조:

```text
SQS -> Lambda -> SqsBatchProcessor -> EventDispatcher -> Handler -> Service -> Redis
```

### 4.1 SQS metric

수집 방식: CloudWatch.

| 목적 | CloudWatch metric | 설명 |
| --- | --- | --- |
| waiting messages | `ApproximateNumberOfMessagesVisible` | queue backlog |
| in-flight messages | `ApproximateNumberOfMessagesNotVisible` | Lambda가 받아 처리 중인 message |
| delayed messages | `ApproximateNumberOfMessagesDelayed` | delay 상태 message |
| oldest message age | `ApproximateAgeOfOldestMessage` | 처리 지연 시간 |
| sent messages | `NumberOfMessagesSent` | 발행량 |
| received messages | `NumberOfMessagesReceived` | 소비량 |
| deleted messages | `NumberOfMessagesDeleted` | 정상 처리 후 삭제량 |
| empty receives | `NumberOfEmptyReceives` | 빈 polling |
| DLQ messages | DLQ `ApproximateNumberOfMessagesVisible` | DLQ 적재량 |
| DLQ oldest age | DLQ `ApproximateAgeOfOldestMessage` | DLQ 방치 시간 |

필수 dimension:

```text
QueueName
```

권장 queue 구분:

```text
cache-refresh-queue
readmodel-refresh-queue
environment-cache-refresh-queue
dead-letter-queue
```

### 4.2 Lambda metric

수집 방식: CloudWatch.

| 목적 | CloudWatch metric | 설명 |
| --- | --- | --- |
| invocations | `Invocations` | Lambda 실행 수 |
| errors | `Errors` | Lambda 실패 수 |
| throttles | `Throttles` | 동시성 제한으로 throttling |
| duration | `Duration` | 실행 시간 |
| concurrent executions | `ConcurrentExecutions` | 동시 실행 수 |
| iterator age | `IteratorAge` | stream source 사용 시 지연 |
| async event age | `AsyncEventAge` | async invoke 사용 시 event age |
| timeout | `Errors` + Lambda log | timeout 상세는 log 확인 |
| memory pressure | `Max Memory Used` in REPORT log | CloudWatch Logs Insights로 추출 |

필수 dimension:

```text
FunctionName
Resource
```

### 4.3 Worker custom metric

SQS/Lambda native metric은 queue/function 상태를 보여준다. 어떤 event type이 실패했는지, Redis refresh가 성공했는지는 worker code에서 별도로 남긴다.

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `worker_processed_total` | Counter | `event_type`, `result`, `queue_name` | 처리 완료 수. `result=success|failure|skipped` |
| `worker_success_total` | Counter | `event_type`, `queue_name` | 처리 성공 |
| `worker_failures_total` | Counter | `event_type`, `reason`, `queue_name` | 처리 실패 |
| `worker_processing_duration_seconds` | Timer | `event_type`, `queue_name` | message 처리 시간 |
| `worker_idempotency_skipped_total` | Counter | `event_type`, `queue_name` | idempotency 중복 skip |
| `worker_redis_write_total` | Counter | `event_type`, `operation`, `result` | Redis SET/DEL 결과 |
| `worker_batch_size` | DistributionSummary | `queue_name` | Lambda batch size |
| `worker_partial_batch_failure_total` | Counter | `event_type`, `queue_name` | partial batch failure 발생 |
| `worker_dlq_publish_total` | Counter | `event_type`, `reason` | DLQ 전송 또는 DLQ 대상 실패 |

재난 read model regeneration 관측에는 다음 cache family 지표를 추가하는 것을 권장한다:

| metric | type | label | 설명 |
| --- | --- | --- | --- |
| `cache_regeneration_requested_total` | Counter | `cache_key_family`, `event_type`, `reason`, `schema_version` | `CacheRegenerationRequested` 수신 또는 downstream rebuild 요청 수 |
| `cache_regeneration_completed_total` | Counter | `cache_key_family` | read model rebuild 완료 |
| `cache_regeneration_failed_total` | Counter | `cache_key_family`, `reason` | read model rebuild 실패 |
| `redis_hot_key_detected_total` | Counter | `cache_key_family` | hot key 감지 |
| `redis_payload_size_bytes` | Gauge or Summary | `cache_key_family` | read model payload size |

권장 `cache_key_family` 값:

```text
disaster_messages_recent
disaster_message_core
disaster_messages_list
disaster_detail
```

권장 `event_type` 값:

```text
EvacuationEntryCreated
EvacuationEntryExited
EvacuationEntryUpdated
ShelterUpdated
DisasterDataCollected
EnvironmentDataCollected
CacheRegenerationRequested
```

`CacheRegenerationRequested` 처리 log에는 `cacheKey`, `cacheKeyFamily`, `reason`, `schemaVersion`을 포함한다.

`disaster:messages:list:seoul`는 low-cardinality MVP 설계의 결과로 hot key 후보가 될 수 있다. 이는 낮은 Redis cardinality를 위한 허용된 MVP trade-off이며, hit ratio, payload size, hot key metric을 함께 관찰해야 한다.

권장 failure reason 값:

```text
invalid_event
idempotency_error
redis_error
db_error
serialization_error
timeout
partial_batch_failure
unknown
```

### 4.4 Async-worker structured log

처리 성공 log:

```json
{
  "level": "INFO",
  "service": "async-worker",
  "event": "worker_processed",
  "traceId": "uuid",
  "awsRequestId": "lambda-request-id",
  "messageId": "sqs-message-id",
  "queueName": "cache-refresh-queue",
  "eventId": "uuid",
  "eventType": "EvacuationEntryCreated",
  "idempotencyKey": "entry:301:ENTERED",
  "result": "success",
  "durationMs": 35,
  "redisOperations": [
    {
      "operation": "SET",
      "key": "shelter:status:10",
      "ttlSeconds": 30,
      "result": "success"
    }
  ]
}
```

처리 실패 log:

```json
{
  "level": "ERROR",
  "service": "async-worker",
  "event": "worker_failed",
  "traceId": "uuid",
  "awsRequestId": "lambda-request-id",
  "messageId": "sqs-message-id",
  "queueName": "cache-refresh-queue",
  "eventId": "uuid",
  "eventType": "EvacuationEntryCreated",
  "idempotencyKey": "entry:301:ENTERED",
  "receiveCount": 3,
  "reason": "redis_error",
  "errorCode": "REDIS_TIMEOUT",
  "result": "failure"
}
```

DLQ 관련 log:

```json
{
  "level": "ERROR",
  "service": "async-worker",
  "event": "worker_dlq",
  "traceId": "uuid",
  "messageId": "sqs-message-id",
  "queueName": "cache-refresh-dlq",
  "eventId": "uuid",
  "eventType": "ShelterUpdated",
  "idempotencyKey": "shelter:10:UPDATED:uuid-v4",
  "reason": "max_receive_count_exceeded"
}
```

## 5. Redis Infra reference

Redis 자체 상태는 application/worker code가 아니라 redis-exporter로 수집한다.

수집 방식:

```text
redis-exporter -> Prometheus
```

| 목적 | metric |
| --- | --- |
| hit | `redis_keyspace_hits_total` |
| miss | `redis_keyspace_misses_total` |
| hit ratio | `rate(redis_keyspace_hits_total[5m]) / (rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m]))` |
| connected clients | `redis_connected_clients` |
| memory used | `redis_memory_used_bytes` |
| memory max | `redis_memory_max_bytes` |
| memory usage ratio | `redis_memory_used_bytes / redis_memory_max_bytes` |
| evicted keys | `redis_evicted_keys_total` |
| expired keys | `redis_expired_keys_total` |
| uptime | `redis_uptime_in_seconds` |
| slowlog length | `redis_slowlog_length` |
| blocked clients | `redis_blocked_clients` |
| rejected connections | `redis_rejected_connections_total` |

Redis 자체의 CPU, failover, replication lag 같은 ElastiCache 관리형 metric은 CloudWatch에서도 함께 확인한다.

## 6. Aurora PostgreSQL / RDS reference

Application 담당자는 RDS metric을 구현하지 않지만, Redis fallback과 API latency 원인 분석을 위해 함께 본다.

수집 방식:

```text
CloudWatch / RDS Enhanced Monitoring / Performance Insights
```

| 목적 | metric |
| --- | --- |
| CPU | `CPUUtilization` |
| DB connections | `DatabaseConnections` |
| free storage | `FreeStorageSpace` |
| free memory | `FreeableMemory` |
| read latency | `ReadLatency` |
| write latency | `WriteLatency` |
| read IOPS | `ReadIOPS` |
| write IOPS | `WriteIOPS` |
| replica lag | `AuroraReplicaLag` |
| deadlocks | `Deadlocks` |
| DB load | `DBLoad`, `DBLoadCPU`, `DBLoadNonCPU` |

## 7. Dashboard 기준

### 7.1 Application / Redis usage dashboard

필수 panel:

- endpoint별 request rate, p95/p99 latency, 4xx/5xx
- `/shelters/nearby`, `/disaster-alerts`, 재난 overview/core read path 상세
- `api_read_cache_request_total` result 비율
- `api_read_cache_fallback_total` reason별 추이
- `api_read_db_fallback_query_total`, fallback latency
- `cache_hit_total`, `cache_miss_total`의 `cache_key_family`별 추이
- `redis_payload_size_bytes{cache_key_family=\"disaster_messages_list\"}` 추이
- `redis_hot_key_detected_total{cache_key_family=\"disaster_messages_list\"}` 추이
- Redis hit ratio, memory usage, evictions
- RDS connections, CPU, read/write latency
- api-core admin action success/failure
- api-core SQS publish success/failure/retry

### 7.2 Async-worker / SQS / Lambda dashboard

필수 panel:

- SQS visible messages
- SQS not visible messages
- SQS oldest message age
- SQS sent/received/deleted messages
- DLQ visible messages
- DLQ oldest message age
- Lambda invocations/errors/throttles
- Lambda duration p95/p99
- Lambda concurrent executions
- `worker_processed_total` event_type/result별 추이
- `worker_processing_duration_seconds` p95/p99
- `worker_failures_total` reason별 추이
- `worker_redis_write_total` result별 추이
- `cache_regeneration_requested_total`, `cache_regeneration_completed_total`, `cache_regeneration_failed_total`의 `cache_key_family`별 추이
- partial batch failure count

### 7.3 Ingestion dashboard

필수 panel:

- source별 external API call/failure/retry
- source별 external API latency
- `ingestion_last_success_timestamp` 기반 stale source
- records fetched/normalized/failed
- normalization failure reason
- cache refresh event publish success/failure

## 8. Alert 기준

초기 임계값은 dev 부하 테스트 결과로 조정한다.

| 장애 유형 | 알람 조건 예시 | 주 담당 |
| --- | --- | --- |
| API latency | 핵심 API p95 latency가 기준선 초과 | Application |
| API error | 핵심 API 5xx rate 증가 | Application |
| Redis usage 문제 | `api_read_cache_fallback_total{reason="redis_down"}` 증가 | Application |
| Redis miss 폭증 | `api_read_cache_request_total{result="miss"}` 급증 + hit ratio 하락 | Application / Redis Infra |
| DB fallback 증가 | `api_read_db_fallback_query_total` 급증 | Application |
| DB 병목 | RDS connection/CPU/read latency/write latency 증가 | DB/Infra |
| SQS backlog | `ApproximateNumberOfMessagesVisible` 증가 | Async-worker |
| SQS 처리 지연 | `ApproximateAgeOfOldestMessage` 증가 | Async-worker |
| DLQ 발생 | DLQ `ApproximateNumberOfMessagesVisible` > 0 | Async-worker |
| Lambda failure | Lambda `Errors` 증가 | Async-worker |
| Lambda throttle | Lambda `Throttles` 증가 | Async-worker |
| Worker 처리 실패 | `worker_failures_total` 증가 | Async-worker |
| Worker 처리 지연 | `worker_processing_duration_seconds` p95 증가 | Async-worker |
| Ingestion stale | source별 last success가 수집 주기 대비 오래됨 | Application |
| Ingestion 실패 | `ingestion_external_api_failure_total` 또는 `ingestion_normalization_failure_total` 증가 | Application |

## 9. Spike 장애 판단 기준

| 현상 | 우선 의심 영역 | 같이 확인할 metric |
| --- | --- | --- |
| API latency 증가 | Application / Redis / DB | HTTP p95, cache fallback, RDS latency |
| fallback 폭증 | Redis usage / Redis infra | cache request result, Redis hit ratio, Redis evictions |
| DB connection 급증 | Redis miss/down 또는 DB 병목 | DB fallback query, RDS connections |
| SQS backlog 증가 | Async-worker 처리 병목 | Lambda duration, Lambda error/throttle, worker duration |
| DLQ 발생 | Async-worker 처리 실패 | worker failure reason, Lambda logs, DLQ message |
| Lambda throttle 발생 | Lambda concurrency 부족 | ConcurrentExecutions, Throttles, SQS oldest age |
| ALB 5xx 발생 | EKS / Application | pod restart, target health, app 5xx |

정상 spike 대응은 API 응답 유지, Redis hit 유지, SQS backlog 없음, Lambda error/throttle 없음, DB connection 안정, ALB 5xx 없음으로 판단한다.

## 10. 책임 경계

| 영역 | 주 담당 | Application 담당 확인 지점 | Async-worker 담당 확인 지점 |
| --- | --- | --- | --- |
| API latency/error | Application | HTTP metric, app log | 이벤트 처리 지연이 API에 영향 주는지 확인 |
| Redis usage | Application | cache hit/miss/fallback, DB fallback | worker Redis refresh 성공 여부 |
| Redis infra | Redis Infra | hit ratio, eviction 참고 | Redis write 실패 원인 참고 |
| SQS queue | Async-worker | publish success/failure | backlog, age, DLQ |
| Lambda | Async-worker | 없음 | invocation, error, throttle, duration |
| Worker business 처리 | Async-worker | event publish correlation | event_type별 success/failure/duration |
| RDS | DB/Infra | DB fallback, query latency 참고 | worker DB read 실패 참고 |
| Ingestion | Application | source별 수집/정규화/발행 상태 | 수집 이벤트 소비 상태 |
```

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-24 | v1.1 | `api_core_shelter_full_count` 주석 보강. `FULL`은 운영 상태이며 요청 거절 또는 admission control이 아님을 명시 |
