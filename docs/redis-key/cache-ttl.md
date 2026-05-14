# Redis Cache TTL 정책

이 문서는 Redis cache TTL 값의 기준 문서다.

RDS는 원천 데이터로 남는다. TTL은 fallback freshness protection이며 primary regeneration mechanism이 아니다.

## TTL 계약

| Key pattern | TTL | 이유 |
| --- | --- | --- |
| `shelter:status:{shelterId}` | 3600초 + 0~120초 jitter (실제 범위: 3600~3720초) | TTL은 freshness 보장 수단이 아니라 safety cap이다. 최신성은 ingestion/update event 기반 regeneration이 담당한다. 다수 key 동시 만료 분산 목적으로 additive jitter를 적용한다. |
| `shelter:list:seoul:{shelterType}:{disasterType}` | 600 seconds | shelter list membership은 shelter status보다 덜 자주 바뀐다. |
| `shelter:list:{region}:{shelterType}:{disasterType}` | 600 seconds | Seoul namespace와 같은 future list-model policy. |
| `disaster:messages:recent:seoul` | 3600초 + 0~120초 jitter (실제 범위: 3600~3720초) | TTL은 safety cap이다. rebuild 시 동시 만료 분산 목적으로 additive jitter를 적용한다. |
| `disaster:message:core:seoul` | 3600초 + 0~120초 jitter (실제 범위: 3600~3720초) | TTL은 safety cap이다. rebuild 시 동시 만료 분산 목적으로 additive jitter를 적용한다. |
| `disaster:messages:list:seoul` | 3600초 + 0~120초 jitter (실제 범위: 3600~3720초) | TTL은 safety cap이다. rebuild 시 동시 만료 분산 목적으로 additive jitter를 적용한다. |
| `disaster:detail:{alertId}` | 3600초 + 0~120초 jitter (실제 범위: 3600~3720초) | detail payload는 normalization 후 거의 변경되지 않는다. TTL은 safety cap이다. |
| `environment:weather:seoul` | 7200 seconds | environment TTL은 data freshness가 아니라 fallback stability protection이다. |
| `environment:air-quality:seoul` | 7200 seconds | environment TTL은 data freshness가 아니라 fallback stability protection이다. |
| `environment:weather-alert:seoul` | 7200 seconds | environment TTL은 data freshness가 아니라 fallback stability protection이다. |
| `suppress:cache-regeneration:{cacheKeyHash}` | 30 seconds | 같은 target key에 대한 중복 regeneration request를 방지한다. |

## Regeneration 규칙

- TTL은 event-driven regeneration을 대체하지 않는다.
- normalized DB write와 regeneration request는 가능하면 TTL expiry 전에 관련 key를 refresh해야 한다.
- TTL은 event-driven regeneration이 지연되거나 누락될 때 stale cache lifetime을 제한하기 위해 존재한다.

Disaster message rebuild trigger:

- DB에 normalize된 새로운 in-scope disaster message
- cache miss 또는 stale detection 후 `CacheRegenerationRequested`
- downstream worker flow의 explicit rebuild request

Disaster message target mapping:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

## Suppress Window 참고

suppress key는 실제 regeneration target key를 hash input으로 사용해야 한다.

예:

- target key: `disaster:messages:list:seoul`
- suppress key: `suppress:cache-regeneration:{hash("disaster:messages:list:seoul")}`

서로 다른 key family가 우발적으로 하나의 suppress key를 공유하면 안 된다.

## Ownership 참고

- `api-core`는 즉시 제거가 필요한 stale shelter key를 `DEL`로만 invalidate한다.
- `api-public-read`는 miss, stale detection, degraded-mode fallback 후 regeneration을 요청한다.
- `async-worker`는 cache data를 rebuild한다.
- `external-ingestion`은 normalized DB data를 write하고 post-commit event 또는 trigger를 publish하지만, public Redis read model을 rebuild하지 않는다.

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-24 | v1.0 | Redis TTL 기준 문서 추가. shelter/disaster 캐시 TTL, 선택 근거, regeneration trigger 규칙 명시 |
| 2026-04-24 | v1.1 | disaster message read model TTL을 `recent/core/list/detail` 구조로 전환. suppress window TTL 30초와 fallback freshness 원칙 명시 |
| 2026-05-14 | v1.2 | `disaster:messages:*` TTL 300s → 600s로 상향. rebuild 시 동시 만료 thundering herd 방지 목적으로 ±10% jitter 추가. |
| 2026-05-14 | v1.3 | 코드 기준 TTL 10분과 문서 불일치 수정 (30초 → 10분). 동시 만료 완화를 위해 `shelter:status:{shelterId}` ±10% jitter 추가. |
| 2026-05-15 | v1.4 | shelter/disaster cache TTL 1시간(3600초)으로 확대. jitter를 ±10%에서 0~120초 단방향 additive 방식으로 변경. TTL은 safety cap이며 최신성은 event-driven regeneration이 담당함을 명시. |
