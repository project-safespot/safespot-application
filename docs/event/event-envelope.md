> **문서 목적** 이 프로젝트에서 비동기 처리는 "응답을 나중에 주는 구조"가 아니다. 관리자 입력 이후 발생하는 캐시 갱신 및 수집 모델 유지를 분리하여, RDS 정합성과 조회 성능을 동시에 확보하기 위한 구조다. 동기 구간에서는 공식 기록을 RDS에 확정하고, 비동기 구간에서는 Redis 상태를 최신으로 유지한다.

---

## 읽는 순서 안내

```
1. 비동기 처리 대상 표   ← 무엇을 언제 나눌지 전체 그림
2. 이벤트 발행 방식      ← 이벤트를 어떻게 보내는가
3. 이벤트 분류           ← 이벤트 종류 구분 기준
4. 이벤트 스펙           ← payload 입력 / worker 출력 포맷
5. Worker 책임표         ← 누가 무엇을 소비할지
6. 실패·재처리 정책      ← 잘못됐을 때 어떻게 할지
```

---

## 1. 비동기 처리 대상 표

### 1-1. 반드시 동기 처리할 것

> RDS가 source of truth다. 아래 작업이 실패하면 이벤트를 발행하지 않는다.

> **Cache-Aside 원칙** 본 시스템은 Cache-Aside 패턴을 사용하며, Redis miss 시 RDS를 동기적으로 조회한 후 캐시를 재생성한다. 조회 경로에서는 SQS 등 비동기 큐를 사용하지 않으며, Redis miss 시에만 동기적으로 RDS fallback을 수행한다.

> **DEL / SET 분리 기준** 즉시 stale 제거가 필요한 도메인 캐시 키는 `api-core`에서 직접 DEL한다. 재계산 비용이 큰 조회용 데이터의 SET(재생성)은 worker가 비동기로 처리한다. 단, Read Model 내부 정합성 유지를 위한 DEL은 해당 worker에서 수행한다. api-core는 도메인 캐시 키에 대해서만 DEL을 수행하며, Read Model 및 조회 전용 캐시 키는 해당 worker가 관리한다.

| 구분 | 작업 | 이유 |
| --- | --- | --- |
| 인증 | 로그인 / JWT 발급 | 사용자 응답이 즉시 필요 |
| 관리자 Write | `POST /admin/evacuation-entries` 입소 등록 | 원본 기록 생성이 먼저 보장돼야 함 |
| 관리자 Write | `POST /admin/evacuation-entries/{id}/exit` 퇴소 처리 | 현재 상태 변경은 source of truth에 즉시 반영 |
| 관리자 Write | `PATCH /admin/evacuation-entries/{id}` 입소 정보 수정 | 원본 데이터 수정이므로 동기 처리 |
| 관리자 Write | `PATCH /admin/shelters/{shelterId}` 대피소 정보 수정 | 운영 상태 변경은 공식 기록 우선 |
| 감사 | `evacuation_event_history` 기록 | 트랜잭션 경계 안에서 남겨야 정합성 보장 |
| 감사 | `admin_audit_log` 기록 | 법적·운영 감사 로그는 sync 보존 필수 |
| 캐시 무효화 | `shelter:status:{id}` DEL | 입소·퇴소·이송 직후 stale 캐시 즉시 제거 |
| 캐시 무효화 | `shelter:list:{shelter_type}:{disaster_type}` DEL | 대피소 운영 상태 변경 시 목록 캐시 즉시 무효화 |

> **⚠️ 원칙** `evacuation_entry`, `evacuation_event_history`, `admin_audit_log` 는 Redis에 올리지 않는다. RDS만이 공식 기록이다.

---

### 1-2. 비동기 후속 처리로 넘길 것

> RDS 커밋 완료 후 이벤트를 발행하고, worker가 처리한다. API 응답 경로와 완전히 분리된다.

| 작업명 | 설명 | 트리거 이벤트 | 트리거 조건 (세분화) | 소비 컴포넌트 | 목적 |
| --- | --- | --- | --- | --- | --- |
| `RecalculateShelterStatus` | 사용자 조회용 대피소 현재인원·혼잡도 캐시 재생성 | `EvacuationEntryCreated` | 입소 등록 RDS INSERT + 커밋 완료 | `cache-worker` | 사용자 조회용 대피소 상태 최신화 |
| `RecalculateShelterStatus` | 동일 | `EvacuationEntryExited` | 퇴소 처리 RDS UPDATE + 커밋 완료 | `cache-worker` | 동일 |
| `RecalculateShelterStatus` | 동일 | `EvacuationEntryUpdated` | 입소 정보 수정 RDS UPDATE + 커밋 완료. **단, `changedFields`가 현재인원에 영향 없는 경우 worker가 Redis 갱신 없이 종료** | `cache-worker` | 동일 |
| `RecalculateShelterStatus` | 동일 | `ShelterUpdated` | 대피소 정보 수정 RDS UPDATE + 커밋 완료. `api-core`가 `shelter:status:{id}`, `shelter:list:{type}:{disasterType}` DEL 후 이벤트 발행. **`changedFields`에 `capacityTotal` 또는 `shelterStatus` 포함 시에만 worker가 Redis 재계산 수행** | `cache-worker` | 동일 |
| `RebuildDisasterReadModel` | 재난 조회 Read Model 전체 재구성 | `DisasterDataCollected` | `external-ingestion Normalizer`가 수집·정규화·RDS 저장 완료 후 발행. `hasExpiredAlerts: true`인 경우 worker가 `disaster:active:{region}` DEL 후 재생성 수행 | `readmodel-worker` | `GET /disaster-alerts` 조회 최적화 |
| `RebuildEnvironmentCache` | 날씨·대기질 환경 데이터 캐시 재구성 | `EnvironmentDataCollected` | `external-ingestion Normalizer`가 외부 API 수집 완료 후 발행. `collectionType`에 따라 `WEATHER` → `env:weather:{nx}:{ny}`, `AIR_QUALITY` → `env:air:{station_name}` 갱신 | `cache-worker` | 보조 기능 조회 캐시 최신화 |

**설계 의도**

트리거 조건을 이벤트 수신만으로 단순화하지 않고, `changedFields` 기반 조건부 처리를 명시한 이유가 있다. `EvacuationEntryUpdated`는 주소·가족정보 수정처럼 현재인원에 무관한 변경이 대다수다. 이 경우 Redis 재계산을 수행하면 불필요한 RDS COUNT() 쿼리가 발생하고, 재난 상황에서 RDS 부하를 가중시킨다. Worker가 payload를 먼저 검사하고 갱신 필요 여부를 판단하는 구조는, 이벤트의 at-least-once 특성을 유지하면서 불필요한 연산을 차단하는 최소 방어선이다.

---

### 1-3. 캐시 재생성 작업

> **재난 Redis 키 정의 (유일한 정의)** 재난 관련 Redis 키는 아래 3종으로 고정한다. 이후 이벤트 스펙의 Output은 이 정의를 참조한다.

| 키 | 설명 | TTL |
| --- | --- | --- |
| `disaster:active:{region}` | 지역별 현재 활성 재난 목록 | 2분 |
| `disaster:alert:list:{region}:{disasterType}` | 지역+유형별 전체 알림 목록 | 5분 |
| `disaster:detail:{alertId}` | 개별 재난 알림 상세 | 10분 |

| 작업명 | 설명 | 입력 | 출력 Redis Key | TTL | 방식 | 설계 근거 |
| --- | --- | --- | --- | --- | --- | --- |
| `RecalculateShelterStatus` | 사용자 조회용 대피소 현재인원·잔여인원·혼잡도 계산 | `shelterId` | `shelter:status:{shelterId}` | 30초 | RDS COUNT() 후 SET | RDS가 source of truth. 별도 카운터 컬럼 유지 시 트랜잭션 실패로 정합성 붕괴 위험. 재난 상황 즉각 대응을 위해 사용자 TTL을 30초로 설정 (관리자 5분 대비 우선) |
| `RebuildDisasterReadModel` | 지역·재난 유형별 활성 목록·상세 전체 재구성 | `region`, `disasterType` | `disaster:active:{region}` / `disaster:alert:list:{region}:{disasterType}` / `disaster:detail:{alertId}` | 2분 / 5분 / 10분 | 수집 결과 정규화 후 SET. `hasExpiredAlerts: true` 시 `disaster:active:{region}` DEL 후 재생성 | 수집 주기(분 단위) 대비 TTL을 동등 이상으로 설정하여 수집 완료 전 stale 노출 최소화. DEL은 worker가 자체 키에 한해 수행 — Read Model 정합성 유지 책임이 readmodel-worker에 있기 때문 |
| `RebuildEnvironmentCache` | 격자 좌표·측정소 기반 날씨·대기질 데이터 재구성 | 좌표 / 측정소 | `env:weather:{nx}:{ny}` / `env:air:{station_name}` | 120분 | 외부 API 결과 정규화 후 SET | 날씨·대기질은 수집 주기가 길고(1시간 단위) 변화가 느림. stale-while-revalidate 패턴 적용 — TTL 내 오래된 데이터를 반환하면서 background refresh를 트리거. 외부 API fallback 제거로 인해 캐시 유효성이 더 중요해짐 |

**Worker 처리 흐름**

**`cache-worker` — `RecalculateShelterStatus`**

```
1. SQS 메시지 수신 (EvacuationEntryCreated / Exited / Updated / ShelterUpdated)
2. idempotencyKey 검사
   └─ 이미 처리된 키면 → no-op 후 ACK
3. [EvacuationEntryUpdated / ShelterUpdated만] changedFields 검사
   └─ 현재인원 영향 없음 → no-op 후 ACK
4. RDS COUNT() 실행
   SELECT COUNT(*) FROM evacuation_entry
   WHERE shelter_id = :shelterId AND status = 'ENTERED'
5. congestionLevel 계산
   0~49%  → AVAILABLE
   50~74% → NORMAL
   75~99% → CROWDED
   100%   → FULL
6. Redis SET shelter:status:{shelterId} (TTL 30초)
7. ACK (SQS 메시지 삭제)
8. 실패 시 → 재시도 (Exponential Backoff) → 한도 초과 시 DLQ 이동
```

**`readmodel-worker` — `RebuildDisasterReadModel`**

```
1. SQS 메시지 수신 (DisasterDataCollected)
2. idempotencyKey 검사
   └─ source + region + issuedAt 기준 중복이면 → no-op 후 ACK
3. hasExpiredAlerts 검사
   └─ true → disaster:active:{region} DEL (Read Model 정합성 유지)
4. RDS에서 해당 region + disasterType의 활성 재난 조회
5. 정규화 후 Redis SET
   disaster:active:{region}                          (TTL 2분)
   disaster:alert:list:{region}:{disasterType}       (TTL 5분)
   disaster:detail:{alertId} — affectedAlertIds 각각  (TTL 10분)
6. ACK
7. 실패 시 → 재시도 → 한도 초과 시 DLQ 이동
```

**`cache-worker` — `RebuildEnvironmentCache`**

```
1. SQS 메시지 수신 (EnvironmentDataCollected)
2. collectionType 분기
   WEATHER     → env:weather:{nx}:{ny}  (TTL 120분)
   AIR_QUALITY → env:air:{station_name} (TTL 120분)
3. RDS에서 해당 수집 결과 조회 (외부 API 직접 호출 없음)
4. 정규화 후 Redis SET (overwrite)
5. ACK
6. 실패 시 → 재시도 최대 3회 → DLQ 이동
```

> **외부 API 직접 호출 제거 이유** Worker가 외부 API를 직접 호출하면, 외부 API 장애 시 worker 재시도가 외부로 향하고 latency·실패 전파가 발생한다. `external-ingestion Normalizer`가 수집·저장을 완료한 뒤 이벤트를 발행하므로, worker는 RDS에서 이미 저장된 결과만 읽으면 된다.

---

## 2. 이벤트 발행 방식

### 현재 MVP 방식

MVP에서는 단순성을 우선하여 **트랜잭션 커밋 후 이벤트를 직접 발행**한다.

```
RDS 커밋 성공
  → 이벤트 발행 (SQS)
  → worker 소비
```

커밋 성공 후 발행 직전 프로세스가 종료되면 이벤트가 유실될 수 있다. 이 가능성을 인지한 상태에서 재시도·DLQ 정책으로 보완한다.

### 운영 단계 확장 검토

이벤트 유실 방지가 중요해지는 운영 단계에서는 **Outbox 패턴 도입을 검토**한다. 도메인 트랜잭션 커밋과 이벤트 레코드 저장을 동일 트랜잭션으로 묶어 유실 가능성을 원천 차단하는 방식이다.

---

## 3. 이벤트 분류

| 분류 | 설명 | 발행 주체 |
| --- | --- | --- |
| **도메인 이벤트** | 관리자 Write 완료 후 발생하는 비즈니스 사실 | `api-core` |
| **수집 이벤트** | external-ingestion Normalizer의 수집 완료 신호 | `external-ingestion Normalizer` |
| **파생 갱신 작업** | 이벤트를 수신한 worker가 내부적으로 수행하는 캐시·Read Model 갱신 | 각 worker 내부 |

> 도메인 이벤트와 수집 이벤트만 큐를 통해 흐르고, 파생 갱신은 worker 내부에서 처리된다.

---

## 4. 이벤트 스펙

### 공통 Envelope

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED:v1",
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

### 도메인 이벤트

### EVENT-001 · `EvacuationEntryCreated`

**입소 등록 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:ENTERED:v{version}` |

**Input — SQS payload**

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED:v1",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "ENTERED",
    "recordedByAdminId": 7,
    "enteredAt": "2026-04-15T14:00:00+09:00"
  }
}
```

**Output — cache-worker → Redis SET**

```json
// shelter:status:101  (TTL 30초)
{
  "currentOccupancy": 69,
  "availableCapacity": 51,
  "congestionLevel": "NORMAL",
  "shelterStatus": "운영중"
}
```

> `congestionLevel` 계산 기준: 점유율 0~49% → `AVAILABLE` / 50~74% → `NORMAL` / 75~99% → `CROWDED` / 100% → `FULL`

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 5회 / Exponential Backoff (1s → 5s → 30s → 2m → 5m) |
| DLQ | ✅ `dlq-evacuation-events` |
| 중복 소비 | 허용 — `entryId + nextStatus` 기준, 동일 상태 중복 수신 시 no-op |
| 순서 보장 | 전역 순서 불필요. 동일 `entryId` 기준 상태 전이 검증 필요 |

---

### EVENT-002 · `EvacuationEntryExited`

**퇴소 처리 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:EXITED:v{version}` |

**Input — SQS payload**

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryExited",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:EXITED:v1",
  "payload": {
    "entryId": 301,
    "shelterId": 101,
    "nextStatus": "EXITED",
    "recordedByAdminId": 7,
    "exitedAt": "2026-04-15T15:10:00+09:00"
  }
}
```

**Output — cache-worker → Redis SET**

```json
// shelter:status:101  (TTL 30초)
{
  "currentOccupancy": 68,
  "availableCapacity": 52,
  "congestionLevel": "NORMAL",
  "shelterStatus": "운영중"
}
```

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 5회 / Exponential Backoff |
| DLQ | ✅ `dlq-evacuation-events` |
| 중복 소비 | 허용 — `entryId`가 이미 EXITED 상태면 no-op |
| 순서 보장 | 전역 순서 불필요. 동일 `entryId` 기준 상태 전이 검증 필요 |

---

### EVENT-003 · `EvacuationEntryUpdated`

**입소 정보 수정 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `entry:{entryId}:UPDATED` |

**Input — SQS payload**

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryUpdated",
  "occurredAt": "2026-04-15T15:20:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:UPDATED",
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

> `changedFields`는 의미 전달용이며, 변경된 필드명만 포함한다. 개인정보 값 자체는 이벤트 payload에 넣지 않는다.

**Output — cache-worker**

cache-worker는 `entryId` 기준으로 RDS 상태를 확인하여 현재인원 영향 여부를 판단한다. 현재인원에 영향 없는 수정은 Redis 갱신 없이 종료된다.

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 3회 / Exponential Backoff |
| DLQ | ✅ `dlq-evacuation-events` |
| 중복 소비 | 허용 — 동일 변경 반복 시 no-op |
| 순서 보장 | 전역 순서 불필요. 동일 `entryId` 기준 상태 전이 검증 필요 |

---

### EVENT-004 · `ShelterUpdated`

**대피소 운영 정보 수정 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `api-core` |
| 발행 시점 | RDS 커밋 완료 직후. `api-core`가 `shelter:status:{id}`, `shelter:list:{type}:{disasterType}` DEL 후 이벤트 발행 |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `shelter:{shelterId}:UPDATED` |

**Input — SQS payload**

```json
{
  "eventId": "uuid-v4",
  "eventType": "ShelterUpdated",
  "occurredAt": "2026-04-15T15:30:00+09:00",
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "shelter:101:UPDATED",
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

> `changedFields`는 의미 전달용이며, 변경된 필드명만 포함한다.

**Output — cache-worker → Redis SET**

cache-worker는 `changedFields`를 확인하여 현재인원·혼잡도에 영향이 있는 경우에만 Redis를 갱신한다. (`capacityTotal`, `shelterStatus` 변경 시 재계산 수행)

```json
// shelter:status:101  (TTL 30초)
{
  "currentOccupancy": 68,
  "availableCapacity": 0,
  "congestionLevel": "FULL",
  "shelterStatus": "운영중단"
}
```

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 5회 / Exponential Backoff |
| DLQ | ✅ `dlq-evacuation-events` |
| 중복 소비 | 허용 — 이전 상태와 동일하면 no-op. 다른 상태면 최종값 기준 overwrite |
| 순서 보장 | 전역 순서 불필요. 동일 `shelterId` 기준 상태 전이 검증 필요 |

---

### 수집 이벤트

### EVENT-005 · `DisasterDataCollected`

**external-ingestion Normalizer가 수집·정규화·저장 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `external-ingestion Normalizer` |
| 소비자 | `readmodel-worker` |
| `idempotencyKey` 구성 | `collected:disaster:{collectionType}:{region}:{completedAt}` |

**Input — SQS payload**

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

> `hasExpiredAlerts: true`면 readmodel-worker가 Read Model 내부 정합성 유지를 위해 `disaster:active:{region}`을 DEL 후 재생성한다. 이는 DEL 기준 예외로, Read Model 갱신 책임을 가진 worker가 자체 키에 한해 수행하는 것이다.

**Output — readmodel-worker → Redis SET**

재난 Redis 키 구조는 1-3 정의를 따른다.

```json
// disaster:active:서울특별시  (TTL 2분)
[
  {
    "alertId": 55,
    "disasterType": "FLOOD",
    "region": "서울특별시",
    "level": "경계",
    "issuedAt": "2026-04-15T14:00:00+09:00",
    "expiredAt": null
  }
]

// disaster:alert:list:서울특별시:FLOOD  (TTL 5분)
[
  {
    "alertId": 55,
    "level": "경계",
    "issuedAt": "2026-04-15T14:00:00+09:00",
    "expiredAt": null
  },
  {
    "alertId": 56,
    "level": "주의",
    "issuedAt": "2026-04-15T13:00:00+09:00",
    "expiredAt": null
  }
]

// disaster:detail:55  (TTL 10분)
{
  "alertId": 55,
  "disasterType": "FLOOD",
  "region": "서울특별시",
  "level": "경계",
  "message": "서울 한강 수위 급상승. 즉시 대피 바랍니다.",
  "source": "KMA_FLOOD",
  "issuedAt": "2026-04-15T14:00:00+09:00",
  "expiredAt": null
}
```

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 5회 / Exponential Backoff |
| DLQ | ✅ `dlq-disaster-events` |
| 중복 소비 | 허용 — `source + region + issuedAt` 기준 dedupe |

---

### EVENT-006 · `EnvironmentDataCollected`

**external-ingestion Normalizer가 환경 데이터 수집 완료 후 발행**

| 항목 | 내용 |
| --- | --- |
| 발행 주체 | `external-ingestion Normalizer` |
| 소비자 | `cache-worker` |
| `idempotencyKey` 구성 | `collected:env:{collectionType}:{region}:{timeWindow}` |

**Input — SQS payload**

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

**Output — cache-worker → Redis SET**

```json
// env:weather:{nx}:{ny}  (TTL 120분) — WEATHER 수집 시
{
  "nx": 60,
  "ny": 127,
  "temperature": 18.5,
  "weatherCondition": "맑음",
  "forecastedAt": "2026-04-15T15:00:00+09:00"
}

// env:air:{station_name}  (TTL 120분) — AIR_QUALITY 수집 시
{
  "stationName": "종로구",
  "aqi": 42,
  "grade": "좋음",
  "measuredAt": "2026-04-15T15:00:00+09:00"
}
```

| 정책 | 내용 |
| --- | --- |
| 재시도 | 최대 3회 |
| DLQ | ✅ `dlq-environment-events` |
| 중복 소비 | 허용 — `region + collectionType + timeWindow` 기준 overwrite |

---

## 5. Worker 책임표

> **async+worker 영역 정의** async+worker는 `api-core` 또는 compute 영역의 `external-ingestion Normalizer`가 발행한 SQS 캐시 갱신 이벤트를 소비하는 **Lambda 워크로드**다. SQS 캐시 갱신 큐의 소유 및 소비 주체는 async+worker 영역이며, Redis 키 갱신 및 Read Model 재구성을 수행한다. 실패 시 재시도 후 DLQ로 이동한다.

### 5-1. 컴포넌트별 책임 분리

| 컴포넌트 | 실행 환경 | 주요 책임 | 입력 | 출력 |
| --- | --- | --- | --- | --- |
| `cache-worker` | Lambda | SQS 이벤트 소비, 대피소 상태 캐시 재생성, 환경 캐시 재구성 | SQS — 도메인 이벤트, 수집 완료 이벤트 | Redis 상태 키 SET |
| `readmodel-worker` | Lambda | SQS 이벤트 소비, 재난 조회 Read Model 전체 재구성 | SQS — `DisasterDataCollected` | Redis `disaster:active:*`, `disaster:alert:list:*`, `disaster:detail:*` SET / DEL |

---

### 5-2. 컴포넌트 경계 — 하지 않는 것

| 컴포넌트 | 하지 않는 것 | 이유 |
| --- | --- | --- |
| `external-ingestion Normalizer` | Redis 직접 접근 | 수집 완료 이벤트 발행으로 역할 종료. Redis 갱신은 async+worker Lambda 전담 (compute 영역 → async+worker 영역 경계) |
| `api-core` | 캐시 SET (재생성) | Lambda worker 전담. 서버는 도메인 캐시 DEL만 담당 |
| `api-public-read` | RDS 직접 조회 | Redis miss 시만 동기 fallback. 재난 시 RDS 직접 타격 방지 |
| 모든 worker | Read path 비동기 개입 | SQS 등 비동기 큐를 조회 경로에 삽입 금지 |

---

## 6. 실패·재처리 정책

### 6-1. 기본 원칙

| 원칙 | 내용 |
| --- | --- |
| **동기 실패는 즉시 반환** | RDS INSERT 실패 → API 오류 반환. 이벤트 미발행 |
| **이벤트는 커밋 후 직접 발행** | MVP 단순성 우선. 커밋 성공 후 발행 실패 시 재시도 + DLQ로 보완 |
| **비동기 실패는 재시도 후 DLQ** | 캐시·Read Model 갱신 실패는 사용자 요청을 직접 실패시키지 않음 |
| **at-least-once 전제** | 메시지는 중복 수신될 수 있다. "한 번만 온다" 가정 금지 |
| **Redis 장애는 fallback으로 대응** | 조회 경로에서 Redis DOWN → RDS 동기 fallback. 큐로 해결하지 않음 |
| **조회 경로 비동기 금지** | Read path에 SQS 등 비동기 큐 삽입 절대 금지 |

---

### 6-2. 재시도 정책표

| 이벤트 유형 | 실패 원인 예시 | 재시도 | Backoff | DLQ |
| --- | --- | --- | --- | --- |
| 입소·퇴소 후 캐시 재생성 | Redis timeout | 5회 | 1s → 5s → 30s → 2m → 5m | ✅ |
| 재난 수집 완료 후 Read Model 재구성 | Redis 실패·직렬화 실패 | 5회 | 5s → 30s → 2m → 5m → 10m | ✅ |
| 환경 데이터 캐시 재구성 | 외부 API 일시 장애 | 3회 | 1m → 5m → 15m | ✅ |

> **Backoff 수치** 현재 초안 기준이며, 실제 Redis·외부 API 타임아웃 설정에 맞게 운영 단계에서 조정한다.

---

### 6-3. DLQ 처리 기준

| 조건 | DLQ 이동 여부 |
| --- | --- |
| 재시도 한도 초과 | ✅ 이동 |
| payload 스키마 불일치 | ✅ 즉시 이동 |
| 참조 리소스 없음 (`shelterId`, `entryId` 미존재) | ✅ 이동 후 운영 확인 |
| 외부 API 일시 장애 | 재시도 후 이동 |
| Redis 일시 장애 | 재시도 후 이동 |

**DLQ 메시지 필수 포함 필드**

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "failedAt": "2026-04-15T14:05:00+09:00",
  "errorMessage": "Redis connection timeout",
  "retryCount": 5,
  "traceId": "uuid-v4",
  "payload": {}
}
```

---

### 6-4. DLQ 운영 액션

| 상황 | 액션 |
| --- | --- |
| DLQ 메시지 적재 | CloudWatch Alarm + Slack / SNS 알림 즉시 발송 |
| 동일 `eventType` 연속 실패 5건 이상 | 장애 경보 발송 — 외부 API 장애 또는 worker 다운 의심 |
| 동일 `eventId` 반복 실패 감지 | 해당 메시지를 poison message로 판단, 차단 후 원인 분석 |
| 운영자 확인 | DLQ 큐 직접 확인 또는 CloudWatch 로그 / 알람 확인 |
| 재처리 | 원인 파악 후 수동 재투입 원칙. 멱등성이 보장된 이벤트에 한해 자동 재처리 허용 |

---

### 6-5. 중복 소비 허용 정책

| 이벤트 | 중복 허용 | 멱등 처리 기준 |
| --- | --- | --- |
| `EvacuationEntryCreated` | 조건부 허용 | `entryId + nextStatus` — 동일 상태 중복 수신 시 no-op |
| `EvacuationEntryExited` | 조건부 허용 | `entryId`가 이미 EXITED면 no-op |
| `EvacuationEntryUpdated` | 허용 | 동일 변경 반복 시 no-op |
| `ShelterUpdated` | 조건부 허용 | 이전 상태와 동일하면 no-op. 다른 상태면 최종값 기준 overwrite |
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