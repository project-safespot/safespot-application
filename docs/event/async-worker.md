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
- `shelter:list:seoul:{shelterType}:{disasterType}` (near-term planned contract, not fully implemented yet)
- `shelter:list:{region}:{shelterType}:{disasterType}` (near-term planned contract, not fully implemented yet)

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
- near-term planned contract: `CacheRegenerationRequested`가 `shelter:list:seoul:{shelterType}:{disasterType}` 및 `shelter:list:{region}:{shelterType}:{disasterType}`도 rebuild할 수 있다.
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

## 6. EVENT-007 Handling

현재:

- 계약은 존재한다.
- 일부 path는 아직 stub일 수 있다.

목표:

- `api-public-read`가 regeneration request를 emit한다.
- worker가 요청된 key family를 rebuild한다.
- suppress-window behavior는 정확한 target `cacheKey`를 기준으로 한다.

권장 disaster `cacheKeyFamily` handling:

- `disaster_detail`
- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`

## 7. Retry 및 DLQ

- invalid payload는 DLQ로 보낸다.
- transient Redis 또는 RDS failure는 retry한다.
- partial batch failure는 허용된다.
- 조사와 replay를 위해 full envelope metadata가 계속 사용 가능해야 한다.

## 8. 관련 문서

- `docs/event/event-envelope.md`
- `docs/api/api-public_read.md`
- `docs/redis-key/redis-key.md`
