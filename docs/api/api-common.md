# SafeSpot REST API 공통 정책

이 문서는 SafeSpot REST API의 공통 API 규칙을 정의한다.

- `docs/api/api-core.md`는 이 문서를 공통 write-side 정책으로 사용한다.
- `docs/api/api-public_read.md`는 이 문서를 공통 read-side 정책으로 사용한다.

## 1. 범위

현재 구현 정책:

- 인증은 access token만 사용한다.
- public read는 `api-public-read`가 담당한다.
- admin write는 `api-core`가 담당한다.
- event는 DB commit 후 publish한다.

목표 아키텍처 정책:

- 구현 메커니즘이 변경되더라도 event publication은 durable해야 하며 event loss를 허용하지 않는다.

## 2. MVP Region 정책

현재 MVP 범위는 서울만 해당한다.

- 서울 외 요청은 `400 UNSUPPORTED_REGION`을 반환해야 한다.
- 입력 형식이 유효해도 이 규칙을 적용한다.
- 서울 밖의 유효한 `lat` / `lng`도 `UNSUPPORTED_REGION`으로 거절한다.

## 3. 기본 규칙

- Base URL: `https://api.safespot.kr`
- Content type: `application/json`
- Auth header: `Authorization: Bearer {accessToken}`
- Time format: ISO 8601 / RFC 3339
- ID format: numeric IDs backed by BIGINT/BIGSERIAL

## 4. 공통 응답 형식

성공:

```json
{
  "success": true,
  "data": {}
}
```

실패:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request value is invalid."
  }
}
```

## 5. 공통 Error Code

| HTTP | Code | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 형식, 범위, enum이 유효하지 않음 |
| 400 | `MISSING_REQUIRED_FIELD` | 필수 필드 누락 |
| 400 | `UNSUPPORTED_REGION` | 요청이 서울 MVP 범위 밖임 |
| 401 | `UNAUTHORIZED` | token 누락 또는 만료 |
| 401 | `INVALID_CREDENTIALS` | 로그인 실패 |
| 401 | `ACCOUNT_DISABLED` | 비활성 계정 |
| 403 | `FORBIDDEN` | 권한 없음 |
| 404 | `NOT_FOUND` | resource 없음 |
| 409 | `ALREADY_EXITED` | 이미 퇴소한 entry에 대한 퇴소 요청 |
| 500 | `INTERNAL_ERROR` | 내부 서버 오류 |

capacity는 거절 조건이 아니므로 capacity 기반 `409`는 없다.

## 6. 공통 Enums

### 6.1 role

- `USER`
- `ADMIN`

### 6.2 disasterType

- `EARTHQUAKE`
- `FLOOD`
- `LANDSLIDE`

MVP의 public disaster 범위에는 이 세 값만 포함된다.

- 범위 밖 재난 메시지는 public disaster read model을 통해 노출하면 안 된다.
- 외부 raw type 값은 canonical `disasterType`과 다를 수 있다.
- API 및 Redis 계약은 canonical 값만 사용한다.
- raw 값은 표시 또는 감사 목적으로 `rawType`에 별도 포함할 수 있다.

### 6.3 messageCategory

- `ALERT`
- `GUIDANCE`
- `CLEAR`

외부 메시지가 다른 문구를 사용해도 `messageCategory`는 canonical 정규화 값을 사용한다.

### 6.4 level

- `INTEREST`
- `CAUTION`
- `WARNING`
- `CRITICAL`

`level`은 canonical severity enum이다.

- `levelRank` mapping은 고정이다: `INTEREST=1`, `CAUTION=2`, `WARNING=3`, `CRITICAL=4`
- 외부 raw severity 값은 canonical `level`과 다를 수 있다.
- raw severity는 표시 또는 감사 목적으로 `rawLevel`에 별도 포함할 수 있다.

### 6.5 entryStatus

- `ENTERED`
- `EXITED`
- `TRANSFERRED`

### 6.6 congestionLevel

| Value | 의미 |
| --- | --- |
| `AVAILABLE` | 0-49% occupancy |
| `NORMAL` | 50-74% occupancy |
| `CROWDED` | 75-99% occupancy |
| `FULL` | 100% or more occupancy |

`congestionLevel`은 상태 표시자일 뿐이다.

- 요청을 거절하지 않는다.
- 입소를 제한하지 않는다.
- 정원 초과 입소는 허용된다.

## 7. Validation Category

validation 실패는 다음으로 구분한다:

- `VALIDATION_ERROR`
- `MISSING_REQUIRED_FIELD`
- `UNSUPPORTED_REGION`

## 8. 공통 Validation 규칙

| 필드 | 규칙 |
| --- | --- |
| `loginId` | 1-50자, blank-only value 불가 |
| `password` | 1-100자 |
| `lat` | numeric, `-90.0` to `90.0` |
| `lng` | numeric, `-180.0` to `180.0` |
| `radius` | integer, `100` to `5000` |
| `name` | 1-50자 |
| `phoneNumber` | 숫자만 허용, 10-11자 |
| `address` | 최대 255자 |
| `familyInfo` | 최대 100자 |
| `healthStatus` | 최대 200자 |
| `note` | 권장 최대 500자 |
| `reason` | 최대 200자 |
| `region` | MVP 기준 서울 전용 region name |
| `stationName` | 최대 100자 |
| `nx` | integer grid x 값 |
| `ny` | integer grid y 값 |

Region validation 규칙:

- 잘못된 형식의 coordinates -> `VALIDATION_ERROR`
- 서울 밖의 유효한 coordinates -> `UNSUPPORTED_REGION`
- 서울이 아닌 region name -> `UNSUPPORTED_REGION`

## 9. Capacity 정책

capacity는 운영 metric이며 enforcement rule이 아니다.

- over-capacity admission은 허용된다.
- capacity는 거절 조건이 아니다.
- `capacityTotal`, `currentOccupancy`, `availableCapacity`는 운영 필드다.
- `congestionLevel`은 상태만 전달한다.

## 10. Security 및 Logging

- `password_hash`를 반환하지 않는다.
- 주민등록번호 조각을 반환하지 않는다.
- raw personal data를 불필요하게 log로 남기지 않는다.
- public read 응답은 개인정보를 노출하면 안 된다.
- admin audit log는 RDS에 before/after payload를 저장할 수 있지만, event payload에는 불필요한 개인정보를 포함하면 안 된다.

## 11. 관련 문서

- api-core endpoint: `docs/api/api-core.md`
- api-public-read endpoint: `docs/api/api-public_read.md`
- event envelope 및 idempotency: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
