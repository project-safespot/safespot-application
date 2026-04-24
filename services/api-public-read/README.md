# api-public-read

조회 전용 API. Redis 캐시 기반 read path 처리.

- 재난 메시지 read model은 `disaster:messages:recent:seoul`, `disaster:message:core:seoul`, `disaster:messages:list:seoul`, `disaster:detail:{alertId}`를 읽는다.
- `disasterType`과 `messageCategory` 필터링은 Redis key가 아니라 payload 필드 기준으로 처리한다.
- district는 MVP 재난 메시지 list Redis key dimension이 아니다.
- cache miss/stale/degraded case에서 `CacheRegenerationRequested`를 발행할 수 있지만, Redis를 직접 재생성하지는 않는다.
- direct RDS fallback은 degraded-mode 전용이며 target hot path가 아니다.
