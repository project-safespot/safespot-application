# SafeSpot 이벤트 Envelope 명세

> 본 문서는 SafeSpot 시스템에서 발행되는 모든 이벤트의 envelope 구조, 이벤트 분류, 이벤트별 SQS payload를 정의한다.
> Worker 처리 흐름, Output(Redis SET 포맷), 실패·재처리 정책은 `docs/async/async-worker.md`를 참조한다.

---

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

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `eventId` | string (UUID v4) | 이벤트 자체 고유 ID |
| `eventType` | string | 이벤트 타입 식별자 |
| `occurredAt` | ISO 8601 | 이벤트 발생 시각 |
| `producer` | string | 발행 서비스 명 |
| `traceId` | string (UUID v4) | 분산 추적용 |
| `idempotencyKey` | string | 중복 소비 방지용 멱등키 |
| `payload` | object | 실제 도메인 데이터 |

---

## 2. 이벤트 분류

| 분류 | 설명 | 발행 주체 |
| --- | --- | --- |
| **도메인 이벤트** | 관리자 Write 완료 후 발생하는 비즈니스 사실 | `api-core` |
| **수집 이벤트** | external-ingestion Normalizer의 수집 완료 신호 | `external-ingestion Normalizer` |
| **read-path 보상 이벤트** | Redis miss 발생 시 read path에서 캐시 재생성을 요청하는 이벤트. 도메인 변경이 아닌 조회 실패 보상 목적 | `api-public-read` |
| **파생 갱신 작업** | 이벤트를 수신한 worker가 내부적으로 수행하는 캐시·Read Model 갱신 | 각 worker 내부 |

> 도메인 이벤트, 수집 이벤트, read-path 보상 이벤트만 SQS를 통해 흐르고, 파생 갱신은 worker 내부에서 처리된다.

---

## 3. idempotencyKey 구성 규칙

| 이벤트 | 구성 규칙 | 비고 |
| --- | --- | --- |
| `EvacuationEntryCreated` | `entry:{entryId}:ENTERED` | 동일 entryId에서 같은 상태 전이는 1회만 발생 |
| `EvacuationEntryExited` | `entry:{entryId}:EXITED` | 동일 |
| `EvacuationEntryUpdated` | `entry:{entryId}:UPDATED:{eventId}` | 5분 내 동일 entryId 수정이 복수 발생 가능하므로 eventId 포함 |
| `ShelterUpdated` | `shelter:{shelterId}:UPDATED:{eventId}` | 5분 내 동일 shelterId 수정이 복수 발생 가능하므로 eventId 포함 |
| `DisasterDataCollected` | `collected:disaster:{collectionType}:{region}:{completedAt}` | |
| `EnvironmentDataCollected` | `collected:env:{collectionType}:{region}:{timeWindow}` | |
| `CacheRegenerationRequested` | `cache-regen:{cacheKey}:{windowStart}` | suppress window 단위로 dedup |

---

## 4. changedFields 사용 원칙

- `changedFields`는 의미 전달용이며, **변경된 필드명만 포함**한다.
- 개인정보 값 자체(`address`, `healthStatus`, `familyInfo` 등)는 이벤트 payload에 포함하지 않는다.
- worker는 `changedFields`를 기준으로 Redis 갱신 필요 여부를 판단한다.

---

## 5. 이벤트 스펙

### 도메인 이벤트

---

### EVENT-001 · `EvacuationEntryCreated`

**입소 등록 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:ENTERED` |

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

---

### EVENT-002 · `EvacuationEntryExited`

**퇴소 처리 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:EXITED` |

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

---

### EVENT-003 · `EvacuationEntryUpdated`

**입소 정보 수정 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:UPDATED:{eventId}` ※ 5분 내 복수 수정 가능으로 eventId 포함 |
| `idempotencyKey` 구성 | `entry:{entryId}:UPDATED:{eventId}` |

> **eventId 포함 이유** 동일 엔티티에 대한 서로 다른 정상 변경이 TTL 내 dedupe로 차단되지 않도록, 변경 단위 고유 식별자(eventId)를 포함한다. 동일 이벤트 재전달 시에는 eventId가 같으므로 정상적으로 dedupe된다.

**Input — SQS payload**


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

---

### EVENT-004 · `ShelterUpdated`

**대피소 운영 정보 수정 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후. `api-core`가 `shelter:status:{id}`, `shelter:list:{type}:{disasterType}` DEL 후 이벤트 발행 |
| 소비자 | `cache-worker` |

| `idempotencyKey` 구성 | `shelter:{shelterId}:UPDATED:{eventId}` ※ 5분 내 복수 수정 가능으로 eventId 포함 |

| `idempotencyKey` 구성 | `shelter:{shelterId}:UPDATED:{eventId}` |

> **eventId 포함 이유** 동일 대피소에 대한 서로 다른 정상 변경이 TTL 내 dedupe로 차단되지 않도록, 변경 단위 고유 식별자(eventId)를 포함한다. 동일 이벤트 재전달 시에는 eventId가 같으므로 정상적으로 dedupe된다.

**Input — SQS payload**

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

---

### 수집 이벤트

---

### EVENT-005 · `DisasterDataCollected`

**external-ingestion Normalizer가 수집·정규화·저장 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `external-ingestion Normalizer` |
| 소비자 | `readmodel-worker` |
| `idempotencyKey` 구성 | `collected:disaster:{collectionType}:{region}:{completedAt}` |

```json
{
  "eventId": "uuid-v4",
  "eventType": "DisasterDataCollected",
  "occurredAt": "2026-04-15T15:02:00+09:00",
  "producer": "external-ingestion Normalizer",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:disaster:FLOOD:서울특별시:2026-04-15T15:02:00+09:00",
  "payload": {
    "collectionType": "FLOOD",
    "region": "서울특별시",
    "affectedAlertIds": [55, 56],
    "hasExpiredAlerts": true,
    "completedAt": "2026-04-15T15:02:00+09:00"
  }
}
```

> `hasExpiredAlerts: true`면 readmodel-worker가 Read Model 내부 정합성 유지를 위해 `disaster:active:{region}`을 DEL 후 재생성한다.

---

### EVENT-006 · `EnvironmentDataCollected`

**external-ingestion Normalizer가 환경 데이터 수집 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `external-ingestion Normalizer` |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `collected:env:{collectionType}:{region}:{timeWindow}` |

```json
{
  "eventId": "uuid-v4",
  "eventType": "EnvironmentDataCollected",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "external-ingestion Normalizer",
  "traceId": "uuid-v4",
  "idempotencyKey": "collected:env:AIR_QUALITY:서울특별시:2026-04-15T15:00",
  "payload": {
    "collectionType": "AIR_QUALITY",
    "region": "서울특별시",
    "completedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

---

### read-path 보상 이벤트

---

### EVENT-007 · `CacheRegenerationRequested`

**api-public-read가 Redis miss 발생 시 캐시 재생성을 요청하기 위해 발행**

> 도메인 변경 이벤트가 아니다. 조회 경로에서 Redis miss가 발생했을 때 worker에게 해당 키의 캐시 재생성을 위임하는 보상 이벤트다.

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-public-read` |
| 발행 조건 | Redis miss 또는 Redis 장애로 RDS fallback 발생 + suppress window(10초) 통과 |
| 소비자 | `cache-worker` / `readmodel-worker` (cacheKey prefix 기준 분기) |
| `idempotencyKey` 구성 | `cache-regen:{cacheKey}:{windowStart}` |

```json
{
  "eventId": "uuid-v4",
  "eventType": "CacheRegenerationRequested",
  "occurredAt": "2026-04-15T15:05:00+09:00",
  "producer": "api-public-read",
  "traceId": "uuid-v4",
  "idempotencyKey": "cache-regen:shelter:status:101:1744980300",
  "payload": {
    "cacheKey": "shelter:status:101",
    "requestedAt": "2026-04-15T15:05:00+09:00"
  }
}
```

**cacheKey 허용 패턴 및 소비 주체**

| cacheKey 패턴 | 소비 주체 | worker 동작 |
| --- | --- | --- |

| `shelter:status:{shelterId}` | `cache-worker` | COUNT 재계산 후 재적재 |
| `disaster:alert:list:{region}:{disasterType}` | `readmodel-worker` | RDS 조회 후 list 재적재 |
| `disaster:latest:{disasterType}:{region}` | `readmodel-worker` | pointer 재적재 |
| `disaster:detail:{alertId}` | `readmodel-worker` | detail 재적재 |

> Worker 처리 흐름 상세 및 Output(Redis SET 포맷)은 `docs/async/async-worker.md`를 참조한다.

| `EvacuationEntryCreated` | 조건부 허용 | `entryId + nextStatus` — 동일 상태 중복 수신 시 no-op |
| `EvacuationEntryExited` | 조건부 허용 | `entryId`가 이미 EXITED면 no-op |
| `EvacuationEntryUpdated` | 허용 | `entry:{entryId}:UPDATED:{eventId}` — 동일 eventId 재전달 시 no-op. 서로 다른 변경은 각각 처리 |
| `ShelterUpdated` | 허용 | `shelter:{shelterId}:UPDATED:{eventId}` — 동일 eventId 재전달 시 no-op. 서로 다른 변경은 각각 처리 |
| `DisasterDataCollected` | 허용 | `source + region + issuedAt` 기준 dedupe |
| `EnvironmentDataCollected` | 허용 | `region + collectionType + timeWindow` 기준 overwrite |

> **핵심 원칙** 캐시 계층은 overwrite 가능하게 설계한다. 원본 데이터 계층은 중복 INSERT 방지(UNIQUE 제약) 또는 상태 전이 검증으로 보호한다.

---

### 6-6. Redis 장애 시 Fallback

| 상황 | 동작 |
| --- | --- |
| 조회 시 Redis DOWN | Cache-Aside 기반으로 RDS fallback 수행 |
| 캐시 재생성 시 Redis DOWN | worker 재시도 → DLQ 이동. 다음 조회 요청에서 Cache-Aside로 자연 복구 |
| Read path에 비동기 큐 삽입 | ❌ 절대 금지 |

---

## 결론

본 서비스의 비동기 구조는 조회 요청을 지연시키기 위한 구조가 아니다. 관리자 입력 이후 발생하는 상태 캐시 갱신·Read Model 갱신을 분리하여, RDS 정합성과 조회 성능을 동시에 확보하기 위한 구조다.

동기 구간에서는 공식 기록을 RDS에 확정하고, 비동기 구간에서는 Redis 조회용 데이터를 최신 상태로 유지한다. MVP에서는 단순성을 우선하여 트랜잭션 커밋 후 이벤트를 직접 발행하며, 운영 단계에서는 Outbox 패턴 도입을 검토한다.

관리자 대시보드는 별도 worker나 Redis 캐시 없이, `api-core`에서 RDS 조회 후 응답으로 제공한다.

`cache-worker`는 사용자 조회용 대피소 상태 캐시 및 환경 캐시를 담당하고, `readmodel-worker`는 재난 조회 Read Model을 유지한다.

---

## 변경 이력

| 날짜 | 항목 | 내용 |
| --- | --- | --- |
| 2026-04-20 | `RebuildEnvironmentCache` TTL | 60분 → 120분. stale-while-revalidate 패턴 적용, 오래된 데이터 반환 중 background refresh 트리거 |
| 2026-04-22 | `EvacuationEntryUpdated` / `ShelterUpdated` idempotencyKey | 정적 엔티티 단위 키에서 이벤트 단위 키로 변경. `entry:{entryId}:UPDATED:{eventId}`, `shelter:{shelterId}:UPDATED:{eventId}`. TTL 내 동일 엔티티 연속 변경이 dedupe로 누락되는 문제 해소 |
