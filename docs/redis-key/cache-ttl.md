# Redis Cache TTL Policy

This document is the source of truth for Redis cache TTL values.

RDS remains the source of truth. TTL is fallback freshness protection, not the primary regeneration mechanism.

## TTL Contract

| Key pattern | TTL | Reason |
| --- | --- | --- |
| `shelter:status:{shelterId}` | 30 seconds | Shelter occupancy and congestion can change quickly during entry/exit flows. |
| `shelter:list:seoul:{shelterType}:{disasterType}` | 600 seconds | Shelter list membership changes less frequently than shelter status. |
| `shelter:list:{region}:{shelterType}:{disasterType}` | 600 seconds | Same future list-model policy as the Seoul namespace. |
| `disaster:messages:recent:seoul` | 300 seconds | Recent overview should stay fresh. |
| `disaster:message:core:seoul` | 300 seconds | Core message should not remain stale. |
| `disaster:messages:list:seoul` | 300 seconds | Disaster message list should reflect recent updates. |
| `disaster:detail:{alertId}` | 3600 seconds | Detail payload changes rarely after normalization. |
| `suppress:cache-regeneration:{cacheKeyHash}` | 30 seconds | Prevent duplicate regeneration requests for the same target key. |

## Regeneration Rules

- TTL does not replace event-driven regeneration.
- normalized DB writes and regeneration requests should refresh relevant keys before TTL expiry where possible.
- TTL exists to limit stale cache lifetime when event-driven regeneration is delayed or missed.

Disaster message rebuild triggers:

- new in-scope disaster messages normalized into DB
- cache miss or stale detection followed by `CacheRegenerationRequested`
- explicit rebuild requests from downstream worker flow

Disaster message target mapping:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

## Suppress Window Notes

Suppress keys must use the actual regeneration target key as hash input.

Example:

- target key: `disaster:messages:list:seoul`
- suppress key: `suppress:cache-regeneration:{hash("disaster:messages:list:seoul")}`

Different key families must not share one suppress key accidentally.

## Ownership Notes

- `api-core` invalidates stale shelter keys by `DEL` only where immediate removal is required.
- `api-public-read` requests regeneration after fallback or stale detection.
- `async-worker` rebuilds cache data.

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-24 | v1.0 | Redis TTL source-of-truth 문서 추가. shelter/disaster 캐시 TTL, 선택 근거, regeneration trigger 규칙 명시 |
| 2026-04-24 | v1.1 | disaster message read model TTL을 `recent/core/list/detail` 구조로 전환. suppress window TTL 30초와 fallback freshness 원칙 명시 |
