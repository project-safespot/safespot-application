# SafeSpot REST API Common Policy

This document defines shared API rules for SafeSpot REST APIs.

- `docs/api/api-core.md` uses this as the common write-side policy.
- `docs/api/api-public_read.md` uses this as the common read-side policy.

## 1. Scope

Current implementation policy:

- Authentication uses access token only.
- Public reads belong to `api-public-read`.
- Admin writes belong to `api-core`.
- Events are published after DB commit.

Target architecture policy:

- Event publication must remain durable and loss-intolerant even if the implementation mechanism changes.

## 2. MVP Region Policy

Current MVP scope is Seoul only.

- Requests outside Seoul must return `400 UNSUPPORTED_REGION`.
- This applies even when the input format is valid.
- Valid `lat` / `lng` outside Seoul are still rejected with `UNSUPPORTED_REGION`.

## 3. Base Rules

- Base URL: `https://api.safespot.kr`
- Content type: `application/json`
- Auth header: `Authorization: Bearer {accessToken}`
- Time format: ISO 8601 / RFC 3339
- ID format: numeric IDs backed by BIGINT/BIGSERIAL

## 4. Common Response Shape

Success:

```json
{
  "success": true,
  "data": {}
}
```

Failure:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request value is invalid."
  }
}
```

## 5. Common Error Codes

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Invalid format, range, or enum |
| 400 | `MISSING_REQUIRED_FIELD` | Required field is missing |
| 400 | `UNSUPPORTED_REGION` | Request is outside Seoul MVP scope |
| 401 | `UNAUTHORIZED` | Token missing or expired |
| 401 | `INVALID_CREDENTIALS` | Login failed |
| 401 | `ACCOUNT_DISABLED` | Disabled account |
| 403 | `FORBIDDEN` | Permission denied |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `ALREADY_EXITED` | Exit requested for an already exited entry |
| 500 | `INTERNAL_ERROR` | Internal server error |

Capacity is not a rejection condition, so there is no capacity-based `409`.

## 6. Common Enums

### 6.1 role

- `USER`
- `ADMIN`

### 6.2 disasterType

- `EARTHQUAKE`
- `FLOOD`
- `LANDSLIDE`

### 6.3 entryStatus

- `ENTERED`
- `EXITED`
- `TRANSFERRED`

### 6.4 congestionLevel

| Value | Meaning |
| --- | --- |
| `AVAILABLE` | 0-49% occupancy |
| `NORMAL` | 50-74% occupancy |
| `CROWDED` | 75-99% occupancy |
| `FULL` | 100% or more occupancy |

`congestionLevel` is a state indicator only.

- It does not reject requests.
- It does not cap admission.
- Over-capacity admission is allowed.

## 7. Validation Categories

Validation failures are grouped into:

- `VALIDATION_ERROR`
- `MISSING_REQUIRED_FIELD`
- `UNSUPPORTED_REGION`

## 8. Common Validation Rules

| Field | Rule |
| --- | --- |
| `loginId` | 1-50 chars, no blank-only value |
| `password` | 1-100 chars |
| `lat` | numeric, `-90.0` to `90.0` |
| `lng` | numeric, `-180.0` to `180.0` |
| `radius` | integer, `100` to `5000` |
| `name` | 1-50 chars |
| `phoneNumber` | digits only, 10-11 chars |
| `address` | max 255 chars |
| `familyInfo` | max 100 chars |
| `healthStatus` | max 200 chars |
| `note` | recommended max 500 chars |
| `reason` | max 200 chars |
| `region` | Seoul-only region name for MVP |
| `stationName` | max 100 chars |
| `nx` | integer grid x value |
| `ny` | integer grid y value |

Region validation rules:

- malformed coordinates -> `VALIDATION_ERROR`
- valid coordinates outside Seoul -> `UNSUPPORTED_REGION`
- non-Seoul region name -> `UNSUPPORTED_REGION`

## 9. Capacity Policy

Capacity is an operational metric, not an enforcement rule.

- over-capacity admission is allowed
- capacity is not a rejection condition
- `capacityTotal`, `currentOccupancy`, and `availableCapacity` are operational fields
- `congestionLevel` communicates state only

## 10. Security And Logging

- Do not return `password_hash`.
- Do not return national ID fragments.
- Do not log raw personal data unnecessarily.
- Public read responses must not expose personal information.
- Admin audit logs may persist before/after payloads in RDS, but event payloads must not include unnecessary personal data.

## 11. Related Documents

- api-core endpoints: `docs/api/api-core.md`
- api-public-read endpoints: `docs/api/api-public_read.md`
- event envelope and idempotency: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
