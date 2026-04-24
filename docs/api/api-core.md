# SafeSpot REST API - api-core

이 문서는 `api-core` API 계약을 정의한다.

공통 auth, response, error, enum, validation 규칙은 `docs/api/api-common.md`를 따른다.

## 1. 책임

`api-core`가 소유한다:

- authentication
- admin read API
- admin write API
- synchronous RDS transaction
- commit 후 event publication
- `DEL`만 사용하는 Redis invalidation

`api-core`가 소유하지 않는다:

- public read API
- Redis rebuild 실행
- read model rebuild 실행
- external data ingestion

## 2. 현재 구현 vs 목표 상태

현재 구현:

- `api-core`는 RDS를 먼저 commit한다.
- 그 다음 event를 publish한다.
- 즉시 invalidation이 필요할 때 stale Redis key를 삭제한다.

목표 아키텍처:

- publish는 commit 후에만 수행해야 한다.
- publish는 durable해야 한다.
- event loss는 허용되지 않는다.
- direct publish를 안전하게 완료할 수 없으면 replay 가능한 storage 또는 failure channel이 있어야 한다.

## 3. Admin Dashboard 정책

`GET /admin/dashboard` is an operational dashboard.

- `capacityTotal`은 운영 metric이다.
- `currentOccupancy`는 운영 metric이다.
- `availableCapacity`는 운영 metric이다.
- `congestionLevel`은 상태 표시자다.
- capacity는 enforcement rule이 아니다.
- 정책: `정원 초과 입소 허용`

## 4. Event 및 Cache 책임

Event 규칙:

- DB commit 후에만 publish한다.
- publish는 durable해야 한다.
- log-only failure handling은 허용하지 않는다.
- replay 또는 recovery를 위해 full envelope를 보존한다.

Cache 책임 분리:

- `api-core` = `DEL`만 수행
- `async-worker` = rebuild

## 5. Endpoints

### 5.1 POST /auth/login

요청:

```json
{
  "loginId": "admin01",
  "password": "P@ssw0rd!"
}
```

`200` 응답:

```json
{
  "success": true,
  "data": {
    "accessToken": "jwt-token",
    "expiresIn": 1800,
    "user": {
      "userId": 1,
      "username": "admin01",
      "name": "Admin User",
      "role": "ADMIN"
    }
  }
}
```

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 필수 필드 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 형식 | 400 | `VALIDATION_ERROR` |
| 유효하지 않은 credential | 401 | `INVALID_CREDENTIALS` |
| 비활성 계정 | 401 | `ACCOUNT_DISABLED` |

### 5.2 GET /me

역할: `USER`, `ADMIN`

`200` 응답:

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "username": "admin01",
    "name": "Admin User",
    "phoneNumber": "01012345678",
    "role": "ADMIN",
    "isActive": true,
    "createdAt": "2026-04-14T09:00:00+09:00",
    "updatedAt": "2026-04-15T09:00:00+09:00"
  }
}
```

### 5.3 GET /admin/dashboard

역할: `ADMIN`

`200` 응답:

```json
{
  "success": true,
  "data": {
    "summary": {
      "totalShelters": 42,
      "openShelters": 38,
      "fullShelters": 3
    },
    "shelters": [
      {
        "shelterId": 101,
        "shelterName": "Mapo Gymnasium Shelter",
        "capacityTotal": 120,
        "currentOccupancy": 128,
        "availableCapacity": 0,
        "congestionLevel": "FULL",
        "shelterStatus": "OPERATING"
      }
    ]
  }
}
```

참고:

- `FULL`은 상태 signal이며 거절 규칙이 아니다.
- Over-capacity admission은 계속 허용된다.

### 5.4 POST /admin/evacuation-entries

역할: `ADMIN`

목적: shelter entry를 등록한다.

요청:

```json
{
  "shelterId": 101,
  "alertId": 55,
  "userId": null,
  "name": "Kim Safe",
  "phoneNumber": "01012345678",
  "address": "Seoul Mapo-gu ...",
  "familyInfo": "2 adults, 1 child",
  "healthStatus": "None",
  "specialProtectionFlag": true,
  "note": "Walk-in registration"
}
```

`201` 응답:

```json
{
  "success": true,
  "data": {
    "entryId": 301,
    "shelterId": 101,
    "entryStatus": "ENTERED",
    "enteredAt": "2026-04-14T10:20:00+09:00"
  }
}
```

Event publication:

- DB commit 후 수행한다.
- durable publish가 필요하다.
- publish path가 실패하면 full envelope를 recover할 수 있어야 한다.
- payload contract: `docs/event/event-envelope.md`의 `EVENT-001`

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 필수 필드 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 형식 | 400 | `VALIDATION_ERROR` |
| 알 수 없는 shelter | 404 | `NOT_FOUND` |

capacity 기반 rejection은 없다.

### 5.5 POST /admin/evacuation-entries/{entryId}/exit

역할: `ADMIN`

`200` 응답:

```json
{
  "success": true,
  "data": {
    "entryId": 301,
    "entryStatus": "EXITED",
    "exitedAt": "2026-04-14T13:10:00+09:00"
  }
}
```

Event publication:

- DB commit 후 수행한다.
- durable publish가 필요하다.
- payload contract: `EVENT-002`

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 알 수 없는 entry | 404 | `NOT_FOUND` |
| 이미 퇴소한 entry | 409 | `ALREADY_EXITED` |

### 5.6 PATCH /admin/evacuation-entries/{entryId}

역할: `ADMIN`

요청:

```json
{
  "address": "Seoul Mapo-gu ...",
  "familyInfo": "2 adults",
  "healthStatus": "Needs medication",
  "specialProtectionFlag": true,
  "note": "Updated after check-in",
  "reason": "Admin correction"
}
```

`200` 응답:

```json
{
  "success": true,
  "data": {
    "entryId": 301,
    "updatedAt": "2026-04-15T15:20:00+09:00"
  }
}
```

Event publication:

- DB commit 후 수행한다.
- durable publish가 필요하다.
- payload contract: `EVENT-003`

### 5.7 PATCH /admin/shelters/{shelterId}

역할: `ADMIN`

요청:

```json
{
  "capacityTotal": 140,
  "shelterStatus": "OPERATING",
  "manager": "Kim Admin",
  "contact": "02-123-4567",
  "note": "Operational capacity updated",
  "reason": "On-site review"
}
```

`200` 응답:

```json
{
  "success": true,
  "data": {
    "shelterId": 101,
    "updatedAt": "2026-04-15T15:30:00+09:00"
  }
}
```

Cache invalidation:

- 현재 구현: `api-core`는 stale key만 삭제한다.
- `shelter:status:{shelterId}`
- future list key는 `api-core`가 아니라 worker가 rebuild한다.

Event publication:

- DB commit 후 수행한다.
- durable publish가 필요하다.
- event loss는 허용되지 않는다.
- payload contract: `EVENT-004`

## 6. 관련 문서

- common API rules: `docs/api/api-common.md`
- event envelope: `docs/event/event-envelope.md`
- worker rebuild behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
