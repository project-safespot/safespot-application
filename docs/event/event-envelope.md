# SafeSpot Event Envelope

이 문서는 공통 event envelope, idempotency 규칙, event 예시를 정의한다.

worker behavior는 `docs/event/async-worker.md`에 둔다.

## 1. 공통 Envelope

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED",
  "payload": {}
}
```

| Field | Type | 의미 |
| --- | --- | --- |
| `eventId` | string | 고유 event ID |
| `eventType` | string | 계약 event type |
| `occurredAt` | string | event 발생 시각 |
| `producer` | string | event를 생성한 service |
| `traceId` | string | trace ID |
| `idempotencyKey` | string | dedupe key |
| `payload` | object | event payload |

## 2. Publish Durability 요구사항

모든 producer는 다음 규칙을 따라야 한다.

- DB commit 후에만 publish한다.
- publish는 durable해야 한다.
- log-only failure handling은 허용하지 않는다.
- 실패 시 replay 또는 recovery를 위해 full envelope를 보존해야 한다.
- direct publish를 안전하게 완료할 수 없으면 replayable storage 또는 failure channel이 필요하다.

## 3. Idempotency 규칙

현재 canonical key:

| Event | idempotencyKey |
| --- | --- |
| `EvacuationEntryCreated` | `entry:{entryId}:ENTERED` |
| `EvacuationEntryExited` | `entry:{entryId}:EXITED` |
| `EvacuationEntryUpdated` | `entry:{entryId}:UPDATED:{eventId}` |
| `ShelterUpdated` | `shelter:{shelterId}:UPDATED:{eventId}` |
| `DisasterDataCollected` | `collected:disaster:{collectionType}:{region}:{completedAt}` |
| `EnvironmentDataCollected` | `collected:env:{collectionType}:{region}:{timeWindow}` |
| `CacheRegenerationRequested` | `cache-regen:{cacheKeyHash}:{windowStart}` |

`ENTERED`와 `EXITED`에는 version suffix를 사용하지 않는다.

`CacheRegenerationRequested`에서 `cacheKeyHash`는 정확한 target `cacheKey`에서 파생해야 한다.

`collected:env:*`는 event idempotency namespace일 뿐이다. Redis environment read-model key는 `environment:*`를 사용한다.

## 4. Event Type

### EVENT-001 `EvacuationEntryCreated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "ENTERED",
    "recordedByAdminId": 7,
    "enteredAt": "2026-04-15T14:00:00+09:00"
  }
}
```

### EVENT-002 `EvacuationEntryExited`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryExited",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:EXITED",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "EXITED",
    "recordedByAdminId": 7,
    "exitedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

### EVENT-003 `EvacuationEntryUpdated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryUpdated",
  "occurredAt": "2026-04-15T15:20:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:UPDATED:uuid-v4",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "recordedByAdminId": 7,
    "updatedAt": "2026-04-15T15:20:00+09:00",
    "changedFields": [
      "address",
      "familyInfo",
      "specialProtectionFlag"
    ]
  }
}
```

### EVENT-004 `ShelterUpdated`

```json
{
  "eventId": "uuid-v4",
  "eventType": "ShelterUpdated",
  "occurredAt": "2026-04-15T15:30:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "shelter:101:UPDATED:uuid-v4",
  "payload": {
    "shelterId": 101,
    "recordedByAdminId": 7,
    "updatedAt": "2026-04-15T15:30:00+09:00",
    "changedFields": [
      "capacityTotal",
      "shelterStatus",
      "note"
    ]
  }
}
```

### EVENT-005 `DisasterDataCollected`

```json
{
  "eventId": "uuid-v4",
  "eventType": "DisasterDataCollected",
  "occurredAt": "2026-04-15T15:02:00+09:00",
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:disaster:FLOOD:seoul:2026-04-15T15:02:00+09:00",
  "payload": {
    "collectionType": "FLOOD",
    "region": "seoul",
    "affectedAlertIds": [55, 56],
    "hasExpiredAlerts": true,
    "completedAt": "2026-04-15T15:02:00+09:00"
  }
}
```

### EVENT-006 `EnvironmentDataCollected`

```json
{
  "eventId": "uuid-v4",
  "eventType": "EnvironmentDataCollected",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:env:AIR_QUALITY:seoul:2026-04-15T15:00",
  "payload": {
    "collectionType": "AIR_QUALITY",
    "region": "seoul",
    "completedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

### EVENT-007 `CacheRegenerationRequested`

목적: read-path cache miss, stale detection, downstream rebuild trigger 후 async Redis read model regeneration을 요청한다.

규칙:

- 이 event는 Redis read model rebuild를 요청한다.
- `api-public-read`가 Redis에 직접 write한다는 의미가 아니다.
- rebuild execution은 `async-worker`가 소유한다.
- `api-public-read`는 cache miss 또는 stale detection 시 request를 publish할 수 있다.
- event flow에 따라 `external-ingestion`은 normalized DB write 후 downstream regeneration을 trigger할 수 있다.

```json
{
  "eventId": "uuid-v4",
  "eventType": "CacheRegenerationRequested",
  "occurredAt": "2026-04-15T15:05:00+09:00",
  "producer": "api-public-read",
  "traceId": "uuid-v4",
  "idempotencyKey": "cache-regen:sha256(disaster:messages:list:seoul):1744980300",
  "payload": {
    "cacheKey": "disaster:messages:list:seoul",
    "cacheKeyFamily": "disaster_messages_list",
    "requestedAt": "2026-04-15T15:05:00+09:00",
    "reason": "cache_miss",
    "schemaVersion": 1
  }
}
```

Payload field:

| Field | 의미 |
| --- | --- |
| `cacheKey` | 정확한 Redis target key |
| `cacheKeyFamily` | logical read-model family |
| `requestedAt` | request 생성 시각 |
| `reason` | regeneration이 요청된 이유 |
| `schemaVersion` | event payload contract version |

권장 `cacheKeyFamily` 값:

- `disaster_messages_recent`
- `disaster_message_core`
- `disaster_messages_list`
- `disaster_detail`
- `shelter_status`
- `shelter_list`
- `environment_weather`
- `environment_air_quality`
- `environment_weather_alert`

지원 disaster message cache target:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

지원 environment cache target:

- `environment:weather:seoul`
- `environment:air-quality:seoul`
- `environment:weather-alert:seoul`

retired disaster key는 지원되는 `CacheRegenerationRequested` target이 아니다.

## 5. 관련 문서

- async worker behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
