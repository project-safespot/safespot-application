# SafeSpot REST API - api-core

This document defines the `api-core` API contract.

Common auth, response, error, enum, and validation rules come from `docs/api/api-common.md`.

## 1. Responsibility

`api-core` owns:

- authentication
- admin read APIs
- admin write APIs
- synchronous RDS transactions
- event publication after commit
- Redis invalidation by `DEL` only

`api-core` does not own:

- public read APIs
- Redis rebuild execution
- read-model rebuild execution
- external data ingestion

## 2. Current Implementation vs Target State

Current implementation:

- `api-core` commits RDS first.
- It then publishes the event.
- It deletes stale Redis keys when immediate invalidation is needed.

Target architecture:

- publish must remain after commit
- publish must be durable
- event loss is not allowed
- replayable storage or a failure channel must exist if direct publish cannot complete safely

## 3. Admin Dashboard Policy

`GET /admin/dashboard` is an operational dashboard.

- `capacityTotal` is an operational metric
- `currentOccupancy` is an operational metric
- `availableCapacity` is an operational metric
- `congestionLevel` is a state indicator
- capacity is not an enforcement rule
- policy: `정원 초과 입소 허용`

## 4. Event And Cache Responsibility

Event rules:

- publish only after DB commit
- publish must be durable
- no log-only failure handling
- preserve full envelope for replay or recovery

Cache responsibility split:

- `api-core` = `DEL` only
- `async-worker` = rebuild

## 5. Endpoints

### 5.1 POST /auth/login

Request:

```json
{
  "loginId": "admin01",
  "password": "P@ssw0rd!"
}
```

Response `200`:

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

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing required field | 400 | `MISSING_REQUIRED_FIELD` |
| Invalid format | 400 | `VALIDATION_ERROR` |
| Invalid credentials | 401 | `INVALID_CREDENTIALS` |
| Disabled account | 401 | `ACCOUNT_DISABLED` |

### 5.2 GET /me

Role: `USER`, `ADMIN`

Response `200`:

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

Role: `ADMIN`

Response `200`:

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

Notes:

- `FULL` is a status signal, not a rejection rule.
- Over-capacity admission remains allowed.

### 5.4 POST /admin/evacuation-entries

Role: `ADMIN`

Purpose: register a shelter entry.

Request:

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

Response `201`:

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

- after DB commit
- durable publish required
- full envelope must be recoverable if publish path fails
- payload contract: `EVENT-001` in `docs/event/event-envelope.md`

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Missing required field | 400 | `MISSING_REQUIRED_FIELD` |
| Invalid format | 400 | `VALIDATION_ERROR` |
| Unknown shelter | 404 | `NOT_FOUND` |

There is no capacity-based rejection.

### 5.5 POST /admin/evacuation-entries/{entryId}/exit

Role: `ADMIN`

Response `200`:

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

- after DB commit
- durable publish required
- payload contract: `EVENT-002`

Failures:

| Case | HTTP | Code |
| --- | --- | --- |
| Unknown entry | 404 | `NOT_FOUND` |
| Already exited | 409 | `ALREADY_EXITED` |

### 5.6 PATCH /admin/evacuation-entries/{entryId}

Role: `ADMIN`

Request:

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

Response `200`:

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

- after DB commit
- durable publish required
- payload contract: `EVENT-003`

### 5.7 PATCH /admin/shelters/{shelterId}

Role: `ADMIN`

Request:

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

Response `200`:

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

- current implementation: `api-core` deletes stale keys only
- `shelter:status:{shelterId}`
- future list keys are rebuilt by worker, not by `api-core`

Event publication:

- after DB commit
- durable publish required
- no event loss
- payload contract: `EVENT-004`

## 6. Related Documents

- common API rules: `docs/api/api-common.md`
- event envelope: `docs/event/event-envelope.md`
- worker rebuild behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
