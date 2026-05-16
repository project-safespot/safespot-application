# Async Worker

이 문서는 async worker behavior, retry/DLQ 규칙, cache rebuild ownership을 정의한다.

## 1. 책임

`async-worker`가 소유한다:

- SQS event consumption
- envelope parsing
- idempotency handling
- Redis rebuild
- retry 및 DLQ handling

소유하지 않는다:

- API request handling
- admin write
- external API collection
- disaster message reclassification
- public read hot path의 RDS candidate lookup

## 2. 현재 구현 vs 목표 상태

현재 구현:

- worker는 committed event를 consume한다.
- 일부 regeneration path는 아직 stub일 수 있다.

목표 아키텍처:

- worker-driven rebuild가 문서화된 모든 cache family를 처리한다.
- disaster message rebuild는 normalized DB data만 사용한다.
- event envelope의 durability requirement는 변경되지 않는다.

## 3. Cache Ownership 분리

- `api-core`는 `DEL`만 사용하여 stale shelter key를 invalidate한다.
- `api-public-read`는 miss, stale detection, degraded-mode fallback 후 regeneration을 요청한다.
- `async-worker`는 cache content를 rebuild한다.

## 4. Key Families

Shelter:

- `shelter:status:{shelterId}`
- `shelter:map:item:{shelterId}`
- `shelter:geo:seoul:{disasterType}:{shelterType}`
- `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`

Disaster message read models:

- `disaster:detail:{alertId}`
- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`

Environment:

- `environment:weather:seoul`
- `environment:air-quality:seoul`
- `environment:weather-alert:seoul`

retired disaster key는 rebuild하면 안 된다.

## 5. Rebuild Behavior

### 5.1 Shelter rebuild

Trigger:

- `EvacuationEntryCreated`
- `EvacuationEntryExited`
- `EvacuationEntryUpdated`
- `ShelterUpdated`
- `CacheRegenerationRequested` for shelter keys

동작:

- RDS state를 읽는다.
- `shelter:status:{shelterId}`를 rebuild한다.
- `shelter:map:item:{shelterId}`를 rebuild한다.
- `shelter:geo:seoul:{disasterType}:{shelterType}`를 rebuild한다.
- `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`를 rebuild한다.
- `CacheRegenerationRequested`는 target family 또는 explicit targetIds 기준으로 batch collapse한다.
- `shelter:geo`와 `shelter:map:tile`은 서울 전체 또는 관련 tile batch rebuild를 허용한다.
- 1차에서는 Seoul 전체 rebuild를 허용한다.
- nearby/map hot path에서 RDS candidate lookup fallback을 수행하지 않는다.
- `congestionLevel`은 informational only다.
- capacity는 admission을 거절하지 않는다.

### 5.2 Disaster message rebuild

Trigger:

- `DisasterDataCollected`
- `CacheRegenerationRequested` for disaster message keys

새 in-scope disaster message 후 필수 regeneration 순서:

1. `disaster:detail:{alertId}`
2. `disaster:messages:recent:seoul`
3. `disaster:message:core:seoul`
4. `disaster:messages:list:seoul`

동작:

- normalized DB data만 읽는다.
- worker에서 raw message를 reclassify하지 않는다.
- public disaster Redis read model에서 `isInScope = false` record를 제외한다.
- `disaster:messages:recent:seoul` rebuild 시 Top 5 policy를 적용한다.
- `disaster:messages:list:seoul` rebuild 시 Top 50 policy를 적용한다.
- `disaster:message:core:seoul`은 `isInScope = true`, `levelRank >= 3`, `messageCategory != CLEAR`, `issuedAt DESC`를 사용해 1개 row를 선택한다.
- core candidate가 없으면 `null` 또는 `schemaVersion = 1`인 empty payload wrapper를 write한다.
- `disaster:active`, `disaster:latest:*`, `disaster:alert:list` 같은 retired key를 rebuild하지 않는다.

### 5.3 Environment rebuild

Trigger:

- `EnvironmentDataCollected`
- `CacheRegenerationRequested` for environment keys

동작:

- `environment:weather:seoul`, `environment:air-quality:seoul`, `environment:weather-alert:seoul` 중 하나를 rebuild한다.
- weather rebuild value는 temperature, weatherCondition, precipitationType, precipitation, windSpeed, humidity, forecastedAt을 포함할 수 있다.
- air-quality rebuild value는 aqi/grade 외에 pm10, pm10Grade, pm25, pm25Grade, o3, o3Grade, measuredAt을 포함할 수 있다.

## 6. EVENT-007 Handling

현재:

- 계약은 존재한다.
- 일부 path는 아직 stub일 수 있다.

목표:

- `api-public-read`가 regeneration request를 emit한다.
- worker가 요청된 key family를 rebuild한다.
- suppress-window behavior는 정확한 target `cacheKey`를 기준으로 한다.
- shelter miss와 parse error는 개별 shelter 수만큼 이벤트를 fan-out하지 말고 batch로 collapse한다.
- `CacheRegenerationRequested`는 `SHELTER_STATUS`, `SHELTER_MAP_ITEMS`, `SHELTER_GEO_INDEX`, `SHELTER_MAP_TILES` 같은 target을 명시적으로 표현할 수 있다.

권장 disaster `cacheKeyFamily` handling:

- `disaster_detail`
- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`

권장 shelter `cacheKeyFamily` handling:

- `shelter_status`
- `shelter_map_item`
- `shelter_geo_index`
- `shelter_map_tile`

## 7. Retry 및 DLQ

- invalid payload는 DLQ로 보낸다.
- transient Redis 또는 RDS failure는 retry한다.
- partial batch failure는 허용된다.
- 조사와 replay를 위해 full envelope metadata가 계속 사용 가능해야 한다.

## 8. SQS Queue 구조

### 8.1 Queue 구성

각 queue는 이벤트 역할별로 분리된다.

| Queue | AWS QueueName | 처리 이벤트 |
|---|---|---|
| Cache Refresh | `safespot-{env}-async-worker-sqs-cache-refresh` | `EvacuationEntryCreated`, `EvacuationEntryExited`, `EvacuationEntryUpdated`, `ShelterUpdated`, `CacheRegenerationRequested` (shelter status/item/geo/tile) |
| Readmodel Refresh | `safespot-{env}-async-worker-sqs-readmodel-refresh` | `DisasterDataCollected`, `CacheRegenerationRequested` (disaster message keys) |
| Environment Cache Refresh | `safespot-{env}-async-worker-sqs-environment-cache-refresh` | `EnvironmentDataCollected`, `CacheRegenerationRequested` (environment keys) |

### 8.2 DLQ 구성

각 queue는 전용 DLQ를 가진다 (1:1 구조). 공용 DLQ는 사용하지 않는다.

| DLQ | AWS QueueName | 연결 Queue |
|---|---|---|
| Cache Refresh DLQ | `safespot-{env}-async-worker-dlq-cache-refresh` | Cache Refresh Queue |
| Readmodel Refresh DLQ | `safespot-{env}-async-worker-dlq-readmodel-refresh` | Readmodel Refresh Queue |
| Environment Cache Refresh DLQ | `safespot-{env}-async-worker-dlq-environment-cache-refresh` | Environment Cache Refresh Queue |

DLQ 메시지 보존 기간: 14일 (수동 replay 대상).

### 8.3 Retry / DLQ Routing 규칙

- `maxReceiveCount = 5`: 동일 메시지가 5회 수신 실패 시 해당 queue의 전용 DLQ로 이동
- invalid payload: DLQ로 즉시 전송 (재시도 없음)
- transient 장애 (Redis, DB, network): SQS visibility timeout 내에 retry 허용
- DLQ 이동 후 replay는 수동 운영 작업으로 처리한다

### 8.4 장애 분석 방법

- **SQS backlog 증가**: `ApproximateNumberOfMessagesVisible` 상승 → Lambda 처리 병목 의심
- **Lambda 실패**: Lambda `Errors` / `Throttles` 상승 → worker 코드 또는 동시성 한계 의심
- **DLQ 메시지 발생**: 해당 queue의 DLQ `ApproximateNumberOfMessagesVisible` > 0 → 특정 이벤트 타입 처리 실패 확인 (worker log `queueName` field 기준으로 격리)

queue별 DLQ 분리로 어느 이벤트 유형에서 실패가 집중되는지 즉시 식별 가능하다.

## 9. Lambda 관리

async-worker Terraform이 Lambda를 직접 관리한다. SQS → Lambda event source mapping 포함.

### 9.1 Lambda 함수 구성

| 항목 | 값 |
|---|---|
| function name | `safespot-{env}-async-worker` |
| runtime | `java21` |
| handler | `com.safespot.asyncworker.handler.AsyncWorkerHandler::handleRequest` |
| timeout | 120s (visibility_timeout 180s보다 작아야 함) |
| memory | 512 MB |
| reserved concurrency | 10 (dev 기준) |
| deployment package | ZIP (`services/async-worker/build/distributions/async-worker-lambda-0.0.1-SNAPSHOT.zip`) |
| VPC | private_app_subnet, lambda_sg |

GitHub Actions는 이 ZIP을 `aws lambda update-function-code`로 직접 업로드할 수 있다.
ECR은 container image Lambda로 전환할 때만 필요하다.

### 9.2 SQS → Lambda Event Source Mapping

3개 queue 각각 전용 event source mapping을 생성한다.

| Queue | Mapping resource |
|---|---|
| `safespot-{env}-async-worker-sqs-cache-refresh` | `aws_lambda_event_source_mapping.cache_refresh` |
| `safespot-{env}-async-worker-sqs-readmodel-refresh` | `aws_lambda_event_source_mapping.readmodel_refresh` |
| `safespot-{env}-async-worker-sqs-environment-cache-refresh` | `aws_lambda_event_source_mapping.environment_cache_refresh` |

공통 설정:
- `batch_size = 10`
- `maximum_batching_window_in_seconds = 5`
- `function_response_types = ["ReportBatchItemFailures"]`

`ReportBatchItemFailures`: batch 내 일부 메시지만 실패해도 해당 메시지만 retry 또는 DLQ로 이동. 성공 메시지는 재처리하지 않는다.

### 9.3 Ops Monitoring 계약

ops는 async-worker Terraform remote state에서 다음 값을 읽어 CloudWatch 알람/대시보드를 구성한다.

| output | 값 (dev) | 용도 |
|---|---|---|
| `lambda_function_name` | `safespot-dev-async-worker` | CloudWatch `FunctionName` dimension |
| `lambda_reserved_concurrent_executions` | `10` | ConcurrentExecutions 알람 임계값 |
| `sqs_dlq_name_cache_refresh` | `safespot-dev-async-worker-dlq-cache-refresh` | Cache Refresh DLQ 알람 |
| `sqs_dlq_name_readmodel_refresh` | `safespot-dev-async-worker-dlq-readmodel-refresh` | Readmodel Refresh DLQ 알람 |
| `sqs_dlq_name_environment_cache_refresh` | `safespot-dev-async-worker-dlq-environment-cache-refresh` | Environment Cache Refresh DLQ 알람 |

## 10. 관련 문서

- `docs/event/event-envelope.md`
- `docs/api/api-public_read.md`
- `docs/redis-key/redis-key.md`
- `docs/monitoring/monitoring.md`
