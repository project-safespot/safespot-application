# Redis Cache TTL Policy

This document is the source of truth for Redis cache TTL values.

RDS remains the source of truth. TTL controls cache freshness, fallback behavior, and regeneration cadence.

## TTL Contract

| Key pattern | TTL | Reason | Regeneration trigger |
| --- | --- | --- | --- |
| `shelter:status:{shelterId}` | 30 seconds | Shelter occupancy and congestion can change quickly during entry/exit flows. Short TTL limits stale operational state while keeping cache hit value. | Rebuild after shelter-related events or `CacheRegenerationRequested`; immediate stale-key `DEL` may happen from `api-core` |
| `shelter:list:seoul:{shelterType}:{disasterType}` | 10 minutes | Shelter list membership changes less frequently than shelter status. Longer TTL reduces repeated list rebuild cost while allowing bounded staleness. | Rebuild on fallback or worker-driven regeneration after relevant shelter updates |
| `shelter:list:{region}:{shelterType}:{disasterType}` | 10 minutes | Same future list-model policy as the Seoul namespace. Region remains extensible even though current MVP is Seoul only. | Rebuild on fallback or worker-driven regeneration after relevant shelter updates |
| `disaster:latest:{disasterType}:{region}` | 5 minutes | Pointer keys should refresh faster than long-lived reference data so latest alert selection stays current without excessive rebuild churn. | Rebuild on new disaster collection, pointer miss, or `CacheRegenerationRequested` for pointer keys |
| `disaster:detail:{alertId}` | 10 minutes | Alert detail is less volatile than latest selection. A moderate TTL balances detail freshness and reuse across repeated reads. | Rebuild on disaster collection affecting the alert, detail miss, or `CacheRegenerationRequested` for detail keys |

## Regeneration Rules

- Redis hit returns cached data.
- Redis miss, Redis down, or parse error falls back to RDS.
- After fallback, the caller publishes a regeneration request subject to the suppress window policy.
- Pointer and detail regeneration are split:
- pointer miss -> regenerate `disaster:latest:{disasterType}:{region}`
- detail miss -> regenerate `disaster:detail:{alertId}`

## Ownership Notes

- `api-core` invalidates stale keys by `DEL` only where immediate removal is required.
- `api-public-read` requests regeneration after fallback.
- `async-worker` rebuilds cache data.

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-24 | v1.0 | Redis TTL source-of-truth 문서 추가. shelter/disaster 캐시 TTL, 선택 근거, regeneration trigger 규칙 명시 |
