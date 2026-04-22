# SafeSpot REST API — api-core

> 본 문서는 `api-core` 워크로드의 API 엔드포인트 명세다.
> 공통 기준(인증 정책, 응답 구조, 에러 코드, Enum, Validation)은 `docs/api/api-common.md`를 참조한다.
> 이벤트 envelope 및 payload 전문은 `docs/event/event-envelope.md`를 참조한다.

---

## api-core 책임 범위

- 인증
- 관리자 운영 조회
- 관리자 write
- 동기 트랜잭션 처리
- 이벤트 발행 (DB commit 이후)
- Redis 캐시 무효화(DEL)

---

## 구현 시 책임 경계

handler/service 책임은 워크로드 기준으로 분리한다.
공통 DTO/enum/error schema는 공유 가능하지만, api-public-read의 handler/service 로직을 이 워크로드에 혼재시키지 않는다.

---

# 8. 인증 API

## 8.1 POST /auth/login

### 권한

없음

### 목적

로그인 ID/비밀번호를 검증하고 Access Token을 발급한다.

### Request

```json
{
  "loginId": "admin01",
  "password": "P@ssw0rd!"
}
```

### Request Validation

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `loginId` | string | Y | 1~50자 |
| `password` | string | Y | 1~100자 |

### Response 200

```json
{
  "success": true,
  "data": {
    "accessToken": "jwt-token",
    "expiresIn": 1800,
    "user": {
      "userId": 1,
      "username": "admin01",
      "name": "홍길동",
      "role": "ADMIN"
    }
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 필수값 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 형식 오류 | 400 | `VALIDATION_ERROR` |
| 아이디/비밀번호 불일치 | 401 | `INVALID_CREDENTIALS` |
| 비활성 계정 | 401 | `ACCOUNT_DISABLED` |

---

## 8.2 GET /me

### 권한

로그인 사용자 (`USER`, `ADMIN`)

### 목적

현재 로그인한 사용자 기본 정보를 반환한다.

### Response 200

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "username": "admin01",
    "name": "홍길동",
    "phoneNumber": "01012345678",
    "role": "ADMIN",
    "isActive": true,
    "createdAt": "2026-04-14T09:00:00+09:00",
    "updatedAt": "2026-04-15T09:00:00+09:00"
  }
}
```

### 노출 금지

- `password_hash`
- `rrn_front_6`

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |

---

# 10. 관리자 API

## 10.1 GET /admin/dashboard

### 권한

`ADMIN`

### 목적

전체 대피소 상태 요약과 주요 대피소 목록을 반환한다.

### Response 200

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
        "shelterName": "서울시민체육관",
        "shelterType": "민방위대피소",
        "capacityTotal": 120,
        "currentOccupancy": 118,
        "availableCapacity": 2,
        "congestionLevel": "FULL",
        "shelterStatus": "운영중"
      }
    ]
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |

---

## 10.2 GET /admin/evacuation-entries

### 권한

`ADMIN`

### 목적

대피소별 입소자 목록을 조회한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `shelterId` | number | Y | 존재하는 대피소 ID |
| `status` | string | N | `ENTERED` / `EXITED` / `TRANSFERRED` |

### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "entryId": 301,
        "shelterId": 101,
        "alertId": 55,
        "userId": null,
        "visitorName": "홍길동",
        "visitorPhone": "01012345678",
        "entryStatus": "ENTERED",
        "enteredAt": "2026-04-14T10:20:00+09:00",
        "exitedAt": null,
        "note": "현장 등록",
        "detail": {
          "address": "서울특별시 마포구 ...",
          "familyInfo": "배우자 1, 자녀 2",
          "healthStatus": "당뇨",
          "specialProtectionFlag": true
        }
      }
    ]
  }
}
```

> 현재 MVP에서는 `detail` 객체를 응답에 포함한다.
> 향후 상세 조회 요구가 증가하면 `GET /admin/evacuation-entries/{entryId}` 로 분리할 수 있다.

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `shelterId` | 404 | `NOT_FOUND` |
| `status` 값 오류 | 400 | `VALIDATION_ERROR` |

---

## 10.3 POST /admin/evacuation-entries

### 권한

`ADMIN`

### 목적

입소자를 등록한다.

### Request

```json
{
  "shelterId": 101,
  "alertId": 55,
  "userId": null,
  "name": "홍길동",
  "phoneNumber": "01012345678",
  "address": "서울특별시 마포구 ...",
  "familyInfo": "배우자 1, 자녀 2",
  "healthStatus": "당뇨",
  "specialProtectionFlag": true,
  "note": "현장 등록"
}
```

### Request Validation

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `shelterId` | number | Y | 존재하는 대피소 ID |
| `alertId` | number | N | 존재하는 재난 알림 ID |
| `userId` | number | N | 회원 ID |
| `name` | string | Y | 1~50자 |
| `phoneNumber` | string | N | 숫자 10~11자리 |
| `address` | string | N | 최대 255자 |
| `familyInfo` | string | N | 최대 100자 |
| `healthStatus` | string | N | 최대 200자 |
| `specialProtectionFlag` | boolean | N | 기본값 `false` |
| `note` | string | N | 최대 500자 권장 |

### Response 201

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

> 201 응답은 **등록 성공 확인용 최소 필드만 반환**한다.
> 현재는 등록 직후 상세 조회용 단건 API를 두지 않으며, 필요성이 생기면 후속 상세 조회 API를 별도 도입한다.

### 이벤트 발행

DB commit 완료 후 `EvacuationEntryCreated` 이벤트를 발행한다.
payload 전문은 `docs/event/event-envelope.md` EVENT-001을 참조한다.

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `shelterId` | 404 | `NOT_FOUND` |
| 필수값 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 형식 오류 | 400 | `VALIDATION_ERROR` |
| 대피소 수용 인원 초과 | 409 | `SHELTER_FULL` |

---

## 10.4 POST /admin/evacuation-entries/{entryId}/exit

### 권한

`ADMIN`

### 목적

입소자 퇴소 처리

### Path Parameters

| 파라미터 | 타입 | 필수 |
| --- | --- | --- |
| `entryId` | number | Y |

### Request

```json
{
  "reason": "자택 복귀"
}
```

### Request Validation

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `reason` | string | N | 최대 200자 |

### Response 200

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

### 이벤트 발행

DB commit 완료 후 `EvacuationEntryExited` 이벤트를 발행한다.
payload 전문은 `docs/event/event-envelope.md` EVENT-002를 참조한다.

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `entryId` | 404 | `NOT_FOUND` |
| 이미 퇴소 처리된 입소자 | 409 | `ALREADY_EXITED` |

---

## 10.5 PATCH /admin/evacuation-entries/{entryId}

### 권한

`ADMIN`

### 목적

입소자 정보를 수정한다.

### Path Parameters

| 파라미터 | 타입 | 필수 |
| --- | --- | --- |
| `entryId` | number | Y |

### Request

```json
{
  "address": "서울특별시 마포구 ...",
  "familyInfo": "배우자 1, 자녀 1",
  "healthStatus": "휠체어 필요",
  "specialProtectionFlag": true,
  "note": "현장 확인 후 수정",
  "reason": "정보 교정"
}
```

### Validation

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `address` | string | N | 최대 255자 |
| `familyInfo` | string | N | 최대 100자 |
| `healthStatus` | string | N | 최대 200자 |
| `specialProtectionFlag` | boolean | N | boolean |
| `note` | string | N | 최대 500자 권장 |
| `reason` | string | N | 최대 200자 |

### reason 필드 정책

- `reason`은 **선택 필드**
- 관리자 감사 로그 기록용으로 사용한다
- 응답에는 포함하지 않는다
- 일반 조회 응답에도 노출하지 않는다

### Response 200

```json
{
  "success": true,
  "data": {
    "entryId": 301,
    "updatedAt": "2026-04-15T15:20:00+09:00"
  }
}
```

### 이벤트 발행

DB commit 완료 후 `EvacuationEntryUpdated` 이벤트를 발행한다.
payload 전문은 `docs/event/event-envelope.md` EVENT-003을 참조한다.

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `entryId` | 404 | `NOT_FOUND` |
| 형식 오류 | 400 | `VALIDATION_ERROR` |

---

## 10.6 PATCH /admin/shelters/{shelterId}

### 권한

`ADMIN`

### 목적

대피소 운영 정보를 수정한다.

### Path Parameters

| 파라미터 | 타입 | 필수 |
| --- | --- | --- |
| `shelterId` | number | Y |

### Request

```json
{
  "capacityTotal": 140,
  "shelterStatus": "운영중",
  "manager": "김담당",
  "contact": "02-123-4567",
  "note": "현장 재점검 결과 수용 가능 인원 상향",
  "reason": "현장 재점검"
}
```

### Validation

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `capacityTotal` | number | N | 1 이상 |
| `shelterStatus` | string | N | `운영중` / `운영중단` / `준비중` |
| `manager` | string | N | 최대 50자 |
| `contact` | string | N | 최대 50자 |
| `note` | string | N | 최대 500자 권장 |
| `reason` | string | Y | 최대 200자 |

### reason 필드 정책

- `reason`은 **필수 필드**
- 관리자 감사 로그 기록용으로 사용한다
- 응답에는 포함하지 않는다

### Response 200

```json
{
  "success": true,
  "data": {
    "shelterId": 101,
    "updatedAt": "2026-04-15T15:30:00+09:00"
  }
}
```

### 캐시 무효화

DB commit 완료 후 아래 Redis 키를 즉시 DEL한다.
- `shelter:status:{shelterId}`
- `shelter:list:{shelterType}:{disasterType}`

### 이벤트 발행

캐시 DEL 완료 후 `ShelterUpdated` 이벤트를 발행한다.
payload 전문은 `docs/event/event-envelope.md` EVENT-004를 참조한다.

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `shelterId` | 404 | `NOT_FOUND` |
| `reason` 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 형식 오류 | 400 | `VALIDATION_ERROR` |