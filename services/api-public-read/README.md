# api-public-read

공개 조회 전용 서비스입니다. `ShelterController`, `DisasterAlertController`, `EnvironmentController`가 외부 읽기 엔드포인트를 제공하고, read path는 Redis를 우선 조회한 뒤 필요한 경우에만 제한적으로 RDS fallback을 수행합니다.

## 역할

- 대피소 주변 조회, 타일 조회, 상세 조회
- 재난 메시지 목록/최신 조회
- 날씨/대기질 조회
- cache miss, stale, degraded 상황에서 `CacheRegenerationRequested` 이벤트 발행

이 서비스는 Redis read model을 직접 재생성하지 않습니다. 재생성 작업은 SQS를 통해 `async-worker`가 수행합니다.

## Redis-first read 구조

- 공통 Redis 접근은 `RedisReadCache`가 담당하며, `REDIS_MISS`, `REDIS_DOWN`, `PARSE_ERROR`를 fallback reason으로 구분합니다.
- 재난 목록은 `disaster:messages:list:seoul`, 상세는 `disaster:detail:{alertId}`를 먼저 읽습니다.
- 대피소 조회는 `shelter:geo:*`, `shelter:map:item:*`, `shelter:status:*`, `shelter:map:tile:*` 계열 키를 사용합니다.
- 환경 조회는 `environment:weather:seoul`, `environment:air-quality:seoul`를 먼저 읽습니다.

## Cache miss 이후 DB fallback

- 재난 목록/상세는 Redis miss 이후 RDS repository를 조회하고, 동시에 cache regeneration 요청을 발행합니다.
- 대피소 상태/지도 항목/타일도 Redis miss 시 RDS fallback을 수행할 수 있지만, key 단위 singleflight와 분산 가드가 먼저 적용됩니다.
- 환경 조회는 Redis miss 또는 Redis down 시 최신 RDS 로그를 조회합니다.
- `PARSE_ERROR`인 경우에는 RDS fallback은 가능하지만 regeneration publish는 생략합니다.

## Local singleflight

`FallbackSingleFlight`가 프로세스 내부 중복 fallback을 줄입니다.

- 동일한 `cache + region + logicalKey` 조합에 대해서는 leader 요청 1개만 supplier를 실행합니다.
- follower 요청은 in-flight future를 기다립니다.
- 기본 follower timeout은 `safespot.public-read.fallback-singleflight.timeout-ms`이며 기본값은 2000ms입니다.
- 이 메커니즘은 모든 read path의 전역 lock이 아니라 key 단위 dedupe입니다.

## Redis distributed lock 관련 구현

일부 fallback 경로에는 `DistributedFallbackGuard`가 있습니다.

- 구현 방식: Redis `SETNX`(`setIfAbsent`) + TTL
- 확인된 적용 경로
  - `DisasterAlertReadService`의 detail fallback
  - `ShelterReadService`의 map item batch fallback
  - `ShelterReadService`의 shelter status batch fallback
  - `ShelterReadService`의 map tile fallback

즉, 분산 가드는 존재하지만 모든 public read 경로에 일괄 적용되는 것은 아닙니다.

## Stale / degraded response

코드에서 확인된 stale 또는 degraded 처리는 경로별로 다릅니다.

- 환경 캐시
  cache hit 자체는 반환하되, 관측 시각이 90분보다 오래되면 `STALE` 이유로 regeneration만 요청합니다.
- 재난 상세
  detail key fallback이 singleflight timeout, suppress, distributed block에 걸리면 상세 DTO 대신 list item 수준의 축약 응답으로 돌아갈 수 있습니다.
- 대피소 타일
  miss tile에 대해 `stale:{tileKey}`를 먼저 재시도하고, 그래도 없으면 `degraded=true` 또는 빈 결과를 반환할 수 있습니다.

반면, 모든 캐시 계열에 공통으로 적용되는 범용 stale 저장 정책은 코드에서 확인되지 않았습니다.

## Cache regeneration event 발행

- `SqsCacheRegenerationPublisher`가 cache family를 queue type으로 라우팅합니다.
- shelter 계열은 `CACHE_REFRESH`
- disaster read model 계열은 `READMODEL_REFRESH`
- environment 계열은 `ENVIRONMENT_CACHE_REFRESH`
- queue URL은 `application-dev.yml`의 `CACHE_REFRESH_QUEUE_URL`, `READMODEL_REFRESH_QUEUE_URL`, `ENVIRONMENT_CACHE_REFRESH_QUEUE_URL`로 주입됩니다.
- `publishBatch`, `publishTarget`이 있어 shelter 계열은 batch 단위 재생성 요청도 발행할 수 있습니다.

## 관련 metric

`PublicReadMetricRecorder`와 서비스 코드에서 아래 메트릭이 확인됩니다.

- `safespot.cache.requests`
- `safespot.cache.fallback`
- `safespot.db.fallback.queries`
- `safespot.db.fallback`
- `safespot.fallback.singleflight`
- `safespot.cache.regeneration.requested`

또한 actuator 설정에서 `health`, `metrics`, `prometheus` endpoint가 노출됩니다.

## 구현상 주의점

- direct RDS fallback은 존재하지만 Redis-first read의 예외 경로이며, 항상 hot path로 사용되도록 설계된 것은 아닙니다.
- regeneration publish에는 `SuppressWindowService` 기반 suppress window가 있습니다.
- tile fallback에는 DB negative result를 짧게 억제하기 위한 suppress key가 있습니다.
- Redis distributed lock, stale/degraded 처리, suppression은 모두 확인되지만 서비스별 적용 범위가 다르므로 하나의 일관된 정책으로 단정하면 안 됩니다.
