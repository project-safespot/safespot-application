# async-worker

SQS 이벤트를 소비해 Redis 캐시와 read model을 재구성하는 Lambda worker입니다. 이 서비스는 RDS를 canonical source of truth로 보고, Redis에는 공개 조회용 파생 데이터를 다시 써 넣습니다.

## 역할

- `EvacuationEntryCreated`, `EvacuationEntryExited`, `EvacuationEntryUpdated`, `ShelterUpdated` 처리
- `EnvironmentDataCollected`, `DisasterDataCollected` 처리
- `CacheRegenerationRequested` 처리
- warm-up 계열 이벤트 처리

`api-public-read`는 재생성 요청만 발행하고, 실제 Redis rebuild는 이 서비스가 담당합니다.

## 실행 구조

- Lambda 진입점은 `AsyncWorkerHandler`입니다.
- 시작 시 Spring 컨텍스트를 `async-worker` 프로필로 초기화합니다.
- 실제 배치 처리는 `SqsBatchProcessor`가 수행합니다.
- `SqsBatchProcessor`는 envelope 파싱, idempotency 획득, event dispatch, partial batch failure 반환까지 담당합니다.

현재 코드에는 `cache-worker`, `readmodel-worker` 프로필도 남아 있지만, Lambda 진입점에서 활성화하는 기본 경로는 `async-worker` 프로필입니다.

## 이벤트 책임 분리

`AsyncWorkerConfig`는 아래 handler들을 하나의 dispatcher에 등록합니다.

- shelter/cache 계열
  `EvacuationEntry*`, `ShelterUpdated`, `CacheRegenerationRequested`(shelter 계열)
- environment 계열
  `EnvironmentDataCollected`, `CacheRegenerationRequested`(environment 계열)
- disaster read model 계열
  `DisasterDataCollected`, `CacheRegenerationRequested`(disaster 계열)
- warm-up 계열
  `ShelterStatusWarmupRequested`, `DisasterReadModelWarmupRequested`

즉, 현재 기본 경로에서는 cache worker와 readmodel worker 책임이 `async-worker` 프로필 아래 한 Lambda 진입점에 통합되어 있습니다.

## cache rebuild / read model 갱신 책임

### Shelter 계열

- `ShelterStatusService`
  RDS의 shelter 정보와 현재 입장 인원을 읽어 `shelter:status:{shelterId}`를 다시 계산합니다.
- `ShelterMapReadModelService`
  shelter map item, GEO index, map tile Redis key를 재구성합니다.
  map tile과 GEO index는 temp key 생성 후 rename swap 방식으로 교체합니다.

### Environment 계열

- `EnvironmentCacheService`
  최신 weather / air quality 로그를 읽어 `environment:*` 캐시를 다시 씁니다.
- `WEATHER_ALERT`는 MVP 스키마에 전용 log table이 없어 `no_data` placeholder를 기록합니다.

### Disaster 계열

- `DisasterReadModelService`
  RDS의 canonical 재난 데이터를 읽어 `detail -> recent -> core -> list` 순서로 Redis read model을 재생성합니다.
- `CacheRegenerationRequested`가 `disaster:detail:{alertId}`에 도달하면 개별 detail rebuild를 수행합니다.

## api-public-read와의 책임 분리

- `api-public-read`
  Redis-first read, miss/down 감지, 제한적 RDS fallback, regeneration 이벤트 발행
- `async-worker`
  SQS 소비, idempotency, RDS 조회, Redis write, read model rebuild

따라서 read path에 SQS를 삽입한 구조가 아니라, 읽기 요청과 비동기 재생성 책임을 분리한 구조입니다.

## RDS / Redis 관계

- RDS는 source of truth입니다.
- Redis는 공개 조회용 파생 데이터 저장소입니다.
- worker는 RDS repository를 읽고 `RedisCacheWriter`로 Redis를 갱신합니다.
- `api-public-read`의 direct RDS fallback은 degraded path이며, 정상적인 steady-state read path는 Redis를 전제로 합니다.

## Idempotency와 실패 처리

- `RedisIdempotencyService`는 Redis `SETNX` 기반으로 `PROCESSING` / `COMPLETED` 상태를 관리합니다.
- `SqsBatchProcessor`는 처리 실패 메시지에 대해 `BatchItemFailure`를 반환해 SQS 재시도를 유도합니다.
- envelope 파싱 실패처럼 영구 실패로 분류된 경우에는 DLQ publish 후 ACK 처리합니다.
- `METRICS_NAMESPACE`가 설정되면 `AsyncWorkerHandler`가 CloudWatch meter registry를 사용하고, 종료 시 flush를 수행합니다.

## 관련 metric

`WorkerMetrics`에서 아래 계열 메트릭이 확인됩니다.

- `worker.processed`
- `worker.success`
- `worker.failures`
- `worker.processing.duration`
- `worker.idempotency.skipped`
- `worker.redis.write`
- `worker.batch.size`
- `worker.partial.batch.failure`
- `worker.dlq.publish`
- `cache.regeneration.requested`
- `cache.regeneration.completed`
- `cache.regeneration.failed`
- `redis.payload.size.bytes`

## Notes for reproduction

- 필수 환경 변수: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`
- 선택 환경 변수: `REDIS_PORT`, `METRICS_NAMESPACE`
- 실제 재현에는 Lambda 함수, SQS queue, event source mapping, Redis/RDS 인프라를 별도로 다시 구성해야 합니다.
- 프로젝트 종료 후 운영 리소스는 정리되었으므로, README의 구조 설명은 현재 운영 상태가 아니라 구현 근거 문서로 읽어야 합니다.
