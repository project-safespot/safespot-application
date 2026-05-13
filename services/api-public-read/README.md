# api-public-read

조회 전용 API. Redis 캐시 기반 read path 처리.

- 재난 메시지 read model은 `disaster:messages:recent:seoul`, `disaster:message:core:seoul`, `disaster:messages:list:seoul`, `disaster:detail:{alertId}`를 읽는다.
- `disasterType`과 `messageCategory` 필터링은 Redis key가 아니라 payload 필드 기준으로 처리한다.
- district는 MVP 재난 메시지 list Redis key dimension이 아니다.
- cache miss/stale/degraded case에서 `CacheRegenerationRequested`를 발행할 수 있지만, Redis를 직접 재생성하지는 않는다.
- direct RDS fallback은 degraded-mode 전용이며 target hot path가 아니다.

## Cache Miss Storm Protection

- `shelter:status:{shelterId}`, `disaster:messages:list:seoul`, `disaster:detail:{alertId}` fallback은 cache key와 repository 단위 single-flight를 적용한다.
- 같은 key의 동시 miss에서는 leader 요청 1개만 RDS fallback을 수행한다. follower 요청은 동일 future를 기다린다.
- 서로 다른 key는 병렬로 fallback할 수 있다. 전체 read path를 전역 lock으로 막지 않는다.
- follower 대기 timeout은 `safespot.public-read.fallback-singleflight.timeout-ms`로 제어한다. 기본값은 2000ms다.
- leader 완료, 실패, follower timeout 후 in-flight entry를 정리한다.
- cache parse error는 RDS fallback은 수행할 수 있지만 `CacheRegenerationRequested`는 발행하지 않는다.
- `CacheRegenerationRequested` publish suppress window는 기존 정책을 유지한다. single-flight는 publish suppress가 아니라 DB fallback 중복 억제를 담당한다.
- Redis down과 Redis miss는 metric reason으로 구분한다. single-flight는 key 단위로만 적용되어 Redis down 상황에서도 전체 fallback path를 막지 않는다.

### Metrics

- `fallback_singleflight_leader_total`: key별 fallback leader 발생 수.
- `fallback_singleflight_join_total`: 동일 key in-flight fallback에 합류한 follower 수.
- `fallback_singleflight_timeout_total`: follower 대기 timeout 수.
- `fallback_suppressed_total`: single-flight join으로 개별 DB fallback이 억제된 수.
- `fallback_stale_served_total`: stale cache serve 도입 시 사용할 예약 metric. 현재 구현은 stale cache를 별도 저장하지 않는다.

### Load Test Note

부하 테스트 전에는 async-worker가 canonical read model을 미리 생성했는지 확인한다. 운영 공개 API에는 warming endpoint를 노출하지 않는다. 필요하면 internal job 또는 운영 절차로 `shelter:status:{shelterId}`, `disaster:messages:list:seoul`, `disaster:detail:{alertId}`를 사전 재생성한다.
