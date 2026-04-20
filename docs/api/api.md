이 문서는 단일 API 명세서이지만 내부 구현 워크로드는 두 개다.

- api-core
  - auth
  - /admin/*
  - write transaction
  - event publish
  - Redis DEL

- api-public-read
  - public GET APIs
  - Redis first
  - RDS fallback
  - suppress window

구현 시 반드시 위 책임 경계를 지켜라.
공통 DTO/enum/error schema는 공유 가능하지만,
handler/service 책임은 워크로드 기준으로 분리하라.

# SafeSpot REST API 명세서 (개발 기준 최종본)

> 본 문서는 SafeSpot 재난 대피 서비스의 백엔드 REST API 최종 명세서이다.
> 
> 
> 현재 프로젝트에서 합의된 최신 기준을 우선 반영하며, 기존 문서와 충돌 시 본 문서를 구현 기준으로 사용한다.
> 

---

## 0. 문서 기준 및 범위

### 0.1 현재 기준

- 인증은 **Access Token only** 로 운영한다.
- `GET /me` 는 `app_user` 단일 조회 기준으로 구현한다.
- `admin_audit_log` 는 `payload_before`, `payload_after` 를 유지한다.
- `PATCH /admin/evacuation-entries/{entryId}` 의 `reason` 은 **선택**이다.
- `PATCH /admin/shelters/{shelterId}` 의 `reason` 은 **필수**이다.
- `GET /admin/dashboard` 는 관리자 보호 워크로드인 `api-core` 소속이다.
- 공개 조회 API는 `api-public-read` 워크로드 기준으로 설계한다.
- 수집기 및 기타 비동기 처리 워크로드는 본 문서 범위에서 제외한다.
- 이벤트는 **DB commit 이후 발행**한다.
- Redis fallback 이후 재생성 요청 이벤트는 **API 인스턴스 로컬 suppress window 10초** 기준으로 동일 키당 1회만 발행한다.

### 0.2 범위

본 문서에는 아래가 포함된다.
- 인증 API
- 공개 조회 API
- 관리자 API
- 공통 응답 구조
- 공통 validation / error 규칙
- 이벤트 메시지 envelope 및 payload
- 캐시 fallback / suppress 정책

본 문서에는 아래가 포함되지 않는다.
- worker 내부 처리 로직
- external-ingestion 상세 명세
- 메시징 인프라(SQS/SNS) 구성 상세
- Terraform 리소스 정의

---

## 1. 워크로드 기준

### 1.1 api-core

- 인증
- 관리자 운영 조회
- 관리자 write
- 동기 트랜잭션 처리
- 이벤트 발행
- Redis 캐시 무효화(DEL)

### 1.2 api-public-read

- 공개 shelter 조회
- 공개 재난 조회
- 보조 환경 조회
- Redis 우선 조회
- RDS fallback 처리

---

## 2. Base URL / 공통

### 2.1 Base URL

`https://api.safespot.kr`

### 2.2 Content-Type

`application/json`

### 2.3 인증 헤더

```
Authorization: Bearer {accessToken}
```

### 2.4 시간 형식

모든 시간 필드는 ISO 8601 / RFC3339 문자열을 사용한다.

예:

```
2026-04-15T15:20:00+09:00
```

### 2.5 ID 형식

현재 명세 기준 모든 ID는 number(BIGSERIAL/BIGINT 기반)로 응답한다.

---

## 3. 인증 정책

### 3.1 역할

- `GUEST`
- `USER`
- `ADMIN`

### 3.2 인증 방식

- JWT Access Token only
- Refresh Token 미도입
- Logout API 미도입
- Access Token 만료 시 재로그인

### 3.3 JWT Payload

```json
{
  "sub": "1",
  "role": "ADMIN",
  "username": "admin01",
  "iat": 1760000000,
  "exp": 1760086400
}
```

### 3.4 JWT Payload 규칙

| 필드 | 설명 |
| --- | --- |
| `sub` | 사용자 ID |
| `role` | `USER` 또는 `ADMIN` |
| `username` | 로그인 ID |
| `iat` | 발급 시각 (epoch seconds) |
| `exp` | 만료 시각 (epoch seconds) |

### 3.5 JWT에 넣지 않는 값

- `name`
- `phone`
- `rrn_front_6`
- `address`
- `familyInfo`
- `healthStatus`
- `specialProtectionFlag`

### 3.6 Access Token 만료시간

- 기본: `1800초` (30분)

---

## 4. 공통 응답 구조

### 4.1 성공

```json
{
  "success": true,
  "data": {}
}
```

### 4.2 실패

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "요청 값이 올바르지 않습니다."
  }
}
```

---

## 5. 공통 에러 코드

| HTTP | 에러 코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 요청 값 형식/범위 오류 |
| 400 | `MISSING_REQUIRED_FIELD` | 필수값 누락 |
| 401 | `UNAUTHORIZED` | 토큰 없음 또는 만료 |
| 401 | `INVALID_CREDENTIALS` | 로그인 실패 |
| 401 | `ACCOUNT_DISABLED` | 비활성 계정 |
| 403 | `FORBIDDEN` | 권한 없음 |
| 404 | `NOT_FOUND` | 대상 리소스 없음 |
| 409 | `ALREADY_EXITED` | 이미 퇴소 처리된 입소자 |
| 409 | `SHELTER_FULL` | 대피소 수용 인원 초과 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

---

## 6. 공통 Enum 정의

### 6.1 role

- `USER`
- `ADMIN`

### 6.2 disasterType

- `EARTHQUAKE`
- `FLOOD`
- `LANDSLIDE`

### 6.3 disasterLevel

- `관심`
- `주의`
- `경계`
- `심각`

### 6.4 shelterType

- `민방위대피소`
- `지진옥외대피장소`
- `이재민임시주거시설`
- `이재민임시주거시설(지진겸용)`
- `수해대피소`
- `산사태임시대피소`

### 6.5 shelterStatus

- `운영중`
- `운영중단`
- `준비중`

### 6.6 entryStatus

- `ENTERED`
- `EXITED`
- `TRANSFERRED`

### 6.7 congestionLevel

| 값 | 기준 |
| --- | --- |
| `AVAILABLE` | 점유율 0~49% |
| `NORMAL` | 점유율 50~74% |
| `CROWDED` | 점유율 75~99% |
| `FULL` | 점유율 100% |

> 응답 예시에 표시된 `congestionLevel` 값은 단일 예시이며, 실제 응답에서는 위 enum 중 하나가 반환된다.
> 

---

## 7. 공통 Validation 규칙

| 필드 | 규칙 |
| --- | --- |
| `loginId` | 1~50자, 공백만 불가 |
| `password` | 1~100자 |
| `lat` | -90.0 ~ 90.0 |
| `lng` | -180.0 ~ 180.0 |
| `radius` | 정수, 100 ~ 5000 |
| `name` | 1~50자 |
| `phoneNumber` | 숫자만, 10~11자리 |
| `address` | 최대 255자 |
| `familyInfo` | 최대 100자 |
| `healthStatus` | 최대 200자 |
| `note` | 최대 500자 권장 |
| `reason` | 최대 200자 |
| `region` | 최대 100자 권장 |
| `stationName` | 최대 100자 권장 |

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

# 9. 공개 조회 API

## 9.1 GET /shelters/nearby

### 권한

없음

### 목적

사용자 위치 기준 주변 대피소 목록을 반환한다.

### 설계 원칙

- 클라이언트가 `lat`, `lng`, `radius`를 전송한다.
- 서버는 위치를 저장하지 않는다.
- 거리 계산은 애플리케이션 레이어에서 수행한다.
- 응답은 **통합형**으로 제공한다.
- 프론트는 동일 응답으로 리스트와 지도 핀을 함께 구성한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `lat` | number | Y | -90 ~ 90 |
| `lng` | number | Y | -180 ~ 180 |
| `radius` | number | Y | 100 ~ 5000 |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

### 정렬 / 페이징

- 기본 정렬: `distanceM ASC`
- paging 없음
- 최대 반환 건수: `TBD`
- 구현 초기 내부 보호용 제한값은 둘 수 있으나 API 계약값으로는 아직 확정하지 않음

### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "shelterId": 101,
        "shelterName": "서울시민체육관",
        "shelterType": "민방위대피소",
        "disasterType": "EARTHQUAKE",
        "address": "서울특별시 마포구 월드컵로 240",
        "latitude": 37.5687,
        "longitude": 126.9081,
        "distanceM": 420,
        "capacityTotal": 120,
        "currentOccupancy": 68,
        "availableCapacity": 52,
        "congestionLevel": "NORMAL",
        "shelterStatus": "운영중",
        "updatedAt": "2026-04-14T09:10:00+09:00"
      }
    ]
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| `lat`, `lng`, `radius` 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 범위 초과 | 400 | `VALIDATION_ERROR` |

---

## 9.2 GET /shelters/{shelterId}

### 권한

없음

### 목적

특정 대피소 상세 정보를 반환한다.

### Path Parameters

| 파라미터 | 타입 | 필수 |
| --- | --- | --- |
| `shelterId` | number | Y |

### Response 200

```json
{
  "success": true,
  "data": {
    "shelterId": 101,
    "shelterName": "서울시민체육관",
    "shelterType": "민방위대피소",
    "disasterType": "EARTHQUAKE",
    "address": "서울특별시 마포구 월드컵로 240",
    "latitude": 37.5687,
    "longitude": 126.9081,
    "capacityTotal": 120,
    "currentOccupancy": 68,
    "availableCapacity": 52,
    "congestionLevel": "NORMAL",
    "shelterStatus": "운영중",
    "manager": "김담당",
    "contact": "02-123-4567",
    "note": "지하 1층 이용",
    "updatedAt": "2026-04-14T09:10:00+09:00"
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 존재하지 않는 `shelterId` | 404 | `NOT_FOUND` |

---

## 9.3 GET /disaster-alerts

### 권한

없음

### 목적

지역/재난유형 기준 재난 알림 목록을 반환한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `region` | string | N | 예: `서울특별시` |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "alertId": 55,
        "disasterType": "FLOOD",
        "region": "서울특별시",
        "level": "주의",
        "message": "한강 수위 상승으로 인해 저지대 침수 주의",
        "issuedAt": "2026-04-14T08:55:00+09:00",
        "expiredAt": null
      }
    ]
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| `disasterType` 값 오류 | 400 | `VALIDATION_ERROR` |

> 결과 없음은 오류가 아니며 `200 + items: []` 로 반환한다.
> 

---

## 9.4 GET /disasters/{disasterType}/latest

### 권한

없음

### 목적

**재난 유형 + 지역(region) 기준 최신 재난 상세 1건**을 반환한다.

### Path Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `disasterType` | string | Y | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `region` | string | Y | 예: `서울특별시` |

### Response 200

```json
{
  "success": true,
  "data": {
    "alertId": 55,
    "disasterType": "EARTHQUAKE",
    "region": "서울특별시",
    "level": "주의",
    "message": "서울 인근 지진 감지",
    "issuedAt": "2026-04-14T08:55:00+09:00",
    "expiredAt": null,
    "details": {
      "magnitude": 4.3,
      "epicenter": "경기 북부",
      "intensity": "IV"
    }
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| `region` 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| `disasterType` 값 오류 | 400 | `VALIDATION_ERROR` |
| 데이터 없음 | 404 | `NOT_FOUND` |

---

## 9.5 GET /weather-alerts

### 권한

없음

### 목적

날씨 보조 정보를 반환한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `region` | string | N | 예: `서울특별시` |
| `nx` | number | N | 격자 X |
| `ny` | number | N | 격자 Y |

> `region`, `nx`, `ny` 는 개별적으로는 선택값이지만, **최소 1개 이상은 반드시 제공**해야 한다.
> 

### Response 200

```json
{
  "success": true,
  "data": {
    "region": "서울특별시",
    "nx": 60,
    "ny": 127,
    "temperature": 18.5,
    "weatherCondition": "맑음",
    "forecastedAt": "2026-04-15T15:00:00+09:00"
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| `region`, `nx`, `ny` 모두 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| `nx` / `ny` 형식 오류 | 400 | `VALIDATION_ERROR` |

> 데이터 없음은 오류가 아니며 `200 + data: null` 로 반환한다.
> 

---

## 9.6 GET /air-quality

### 권한

없음

### 목적

대기질 보조 정보를 반환한다.

### Query Parameters

| 파라미터 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `region` | string | N | 예: `서울특별시` |
| `stationName` | string | N | 예: `종로구` |

> `region`, `stationName` 은 개별적으로는 선택값이지만, **최소 1개 이상은 반드시 제공**해야 한다.
> 

### Response 200

```json
{
  "success": true,
  "data": {
    "stationName": "종로구",
    "aqi": 42,
    "grade": "좋음",
    "measuredAt": "2026-04-15T15:00:00+09:00"
  }
}
```

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| `region`, `stationName` 모두 누락 | 400 | `MISSING_REQUIRED_FIELD` |

> 데이터 없음은 오류가 아니며 `200 + data: null` 로 반환한다.
> 

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
> 
> 
> 향후 상세 조회 요구가 증가하면 `GET /admin/evacuation-entries/{entryId}` 로 분리할 수 있다.
> 

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
> 
> 
> 현재는 등록 직후 상세 조회용 단건 API를 두지 않으며, 필요성이 생기면 후속 상세 조회 API를 별도 도입한다.
> 

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

### 실패

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| 토큰 없음 / 만료 | 401 | `UNAUTHORIZED` |
| USER 권한으로 접근 | 403 | `FORBIDDEN` |
| 존재하지 않는 `shelterId` | 404 | `NOT_FOUND` |
| `reason` 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 형식 오류 | 400 | `VALIDATION_ERROR` |

---

# 11. 이벤트 명세

## 11.1 공통 Envelope

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "version": 1,
  "producer": "api-core",
  "traceId": "uuid-v4",
  "idempotencyKey": "entry:301:ENTERED:v1",
  "payload": {}
}
```

### Envelope 필드 정의

| 필드 | 설명 |
| --- | --- |
| `eventId` | 이벤트 고유 ID |
| `eventType` | 이벤트 유형 |
| `occurredAt` | 이벤트 발생 시각 |
| `version` | 이벤트 스키마 버전 |
| `producer` | 발행 주체 (`api-core`) |
| `traceId` | 추적 ID |
| `idempotencyKey` | 중복 방지용 키 |
| `payload` | 이벤트 상세 데이터 |

---

## 11.2 EvacuationEntryCreated

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryCreated",
  "occurredAt": "2026-04-15T14:00:00+09:00",
  "version": 1,
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

---

## 11.3 EvacuationEntryExited

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryExited",
  "occurredAt": "2026-04-15T15:10:00+09:00",
  "version": 1,
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

---

## 11.4 EvacuationEntryUpdated

```json
{
  "eventId": "uuid-v4",
  "eventType": "EvacuationEntryUpdated",
  "occurredAt": "2026-04-15T15:20:00+09:00",
  "version": 1,
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

> `idempotencyKey`는 중복 방지용이다.
> 
> 
> `changedFields`는 의미 전달용이며, **변경된 필드명만 포함**한다.
> 
> 개인정보 값 자체는 이벤트 payload에 넣지 않는다.
> 

---

## 11.5 ShelterUpdated

```json
{
  "eventId": "uuid-v4",
  "eventType": "ShelterUpdated",
  "occurredAt": "2026-04-15T15:30:00+09:00",
  "version": 1,
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
          "manager",
          "contact",
          "note"
    ]
  }
}
```

> `changedFields`는 의미 전달용이며, **변경된 필드명만 포함**한다.
> 
> 
> 개인정보 값 자체는 이벤트 payload에 넣지 않는다.
> 

```

---

# 12. 캐시 및 Fallback 정책

## 12.1 기본 원칙

- 공개 조회는 Redis 우선 조회
- Redis MISS 또는 Redis 장애 시 RDS fallback
- fallback 응답 자체는 즉시 반환
- fallback 이후 캐시 재생성 요청은 별도 이벤트로 전달 가능

## 12.2 Suppress Window

- `api-public-read` 는 Redis MISS 또는 Redis 장애로 fallback 발생 시
- **API 인스턴스 로컬 suppress window 10초** 기준으로 동일 키당 1회만 재생성 요청 이벤트를 발행한다
- suppress window 안의 추가 fallback은 이벤트를 재발행하지 않는다

## 12.3 적용 우선 대상

- `shelter:status:{shelterId}`
- `admin:shelter:status:{shelterId}`

---

# 13. 보안 / 로그 / 개인정보 주의사항

## 13.1 저장 금지 / 노출 금지

- `password_hash` 응답 금지
- `rrn_front_6` 응답 금지
- 위치 정보(`lat`, `lng`) 저장 금지
- 공개 조회 응답에 개인정보 포함 금지

## 13.2 로그 금지

애플리케이션 로그에 아래 직접 기록 금지:
- 이름
- 전화번호
- 주소
- 건강상태
- 가족관계
- 비밀번호
- 주민번호 관련 값

## 13.3 관리자 감사 로그

- `payload_before`, `payload_after` 유지
- append-only
- 관리자도 직접 수정 불가

---

# 14. Observability / Monitoring

## 14.1 공통 원칙

- 모든 서비스는 Prometheus scrape 대상이다.
- `/actuator/prometheus` endpoint를 노출한다.
- 공통 레이블 (모든 metric에 적용):

| 레이블 | 값 |
| --- | --- |
| `service` | `api-core` 또는 `api-public-read` |
| `env` | `dev` / `prod` |
| `region` | `seoul` |
- metric naming prefix 규칙:

| 워크로드 | prefix |
| --- | --- |
| api-core | `api_core_` |
| api-public-read | `api_read_` |
- trace_id 정책:
    - 모든 인입 요청에 대해 `trace_id`를 생성하거나 upstream에서 전달된 값을 수신한다.
    - `trace_id`는 요청 처리 전 구간(downstream 서비스 호출 포함)에 전파한다.
    - 로그 출력 시 `trace_id`를 반드시 포함한다.
- 로그 정책:
    - 애플리케이션 로그에 개인정보 직접 기록 금지 (`name`, `phone`, `address`, `healthStatus`, `familyInfo` 등)
    - 식별자는 `entryId` / `shelterId` / `adminId` 기반으로만 기록한다.

---

## 14.2 워크로드별 메트릭 정의

### 14.2.1 api-public-read

api-public-read는 Redis 우선 조회 → RDS fallback 구조를 운영하며, fallback 발생 여부와 빈도를 계측한다.

**HTTP 공통 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `api_read_http_requests_total` | counter | `service`, `method`, `endpoint`, `status` | 모든 HTTP 응답 완료 시 |
| `api_read_http_request_duration_seconds` | histogram | `service`, `method`, `endpoint`, `status` | 모든 HTTP 응답 완료 시 (요청 수신 ~ 응답 전송 기준) |

> `endpoint`는 path template 기준으로 기록한다. 예: `/shelters/{shelterId}`. path variable 실제 값은 포함하지 않는다.
> 

**Cache / Fallback 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `api_read_cache_fallback_total` | counter | `service`, `endpoint`, `reason` | Redis 조회 결과 MISS 또는 Redis 연결 실패로 RDS fallback이 실행될 때 |
| `api_read_db_fallback_query_total` | counter | `service`, `endpoint` | RDS fallback 쿼리가 실제 실행될 때 |

> `reason` label 허용 값: `redis_miss` / `redis_down`
> 

**Fallback 정책 명세**

- Redis MISS 또는 Redis 장애 발생 시 RDS fallback을 즉시 실행하고 응답을 반환한다.
- fallback 발생 시 `api_read_cache_fallback_total` counter를 증가시킨다. `reason` 레이블로 원인을 구분한다.
- fallback 이후 캐시 재생성 이벤트는 API 인스턴스 로컬 suppress window 10초를 적용하여 동일 키당 1회만 발행한다. suppress window 내 추가 fallback은 이벤트를 재발행하지 않는다.

---

### 14.2.2 api-core

api-core는 인증, 관리자 write, 동기 트랜잭션, 이벤트 발행을 담당하며, 관리자 행위와 입소/퇴소 비즈니스 흐름을 계측한다.

**HTTP 공통 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `api_core_http_requests_total` | counter | `service`, `method`, `endpoint`, `status` | 모든 HTTP 응답 완료 시 |
| `api_core_http_request_duration_seconds` | histogram | `service`, `method`, `endpoint`, `status` | 모든 HTTP 응답 완료 시 (요청 수신 ~ 응답 전송 기준) |

> `endpoint`는 path template 기준으로 기록한다. 예: `/admin/evacuation-entries/{entryId}/exit`
> 

**관리자 행위 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `api_core_admin_api_calls_total` | counter | `service`, `method`, `endpoint`, `status` | ADMIN 권한 요청이 인증 통과 후 응답 완료 시 |
| `api_core_admin_action_total` | counter | `service`, `action` | 관리자 write 작업이 DB commit 완료 시 |
| `api_core_admin_action_failed_total` | counter | `service`, `action` | 관리자 write 작업이 비즈니스 오류 또는 DB 트랜잭션 실패 시. validation 실패(400)는 포함하지 않는다. |

> `action` label 허용 값 (고정 enum):
> 

| action 값 | 대응 API |
| --- | --- |
| `ENTRY_CREATE` | `POST /admin/evacuation-entries` |
| `ENTRY_EXIT` | `POST /admin/evacuation-entries/{entryId}/exit` |
| `ENTRY_UPDATE` | `PATCH /admin/evacuation-entries/{entryId}` |
| `SHELTER_UPDATE` | `PATCH /admin/shelters/{shelterId}` |

**비즈니스 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `api_core_shelter_checkin_total` | counter | `service` | `POST /admin/evacuation-entries` 성공 (DB commit) 시 |
| `api_core_shelter_checkout_total` | counter | `service` | `POST /admin/evacuation-entries/{entryId}/exit` 성공 (DB commit) 시 |
| `api_core_shelter_checkin_failed_total` | counter | `service`, `reason` | 입소 등록 시 409 `SHELTER_FULL` 응답이 반환될 때 |
| `api_core_shelter_full_count` | gauge | `service` | `GET /admin/dashboard` RDS 집계 쿼리 실행 완료 시, 전체 대피소 중 `congestionLevel=FULL` 상태인 대피소 수로 갱신한다. |

> `api_core_shelter_checkin_total` / `api_core_shelter_checkout_total`은 `shelterId`를 label로 사용하지 않는다. `shelterId`는 대피소 수에 비례하여 cardinality가 선형 증가하므로 Prometheus 메모리 문제를 유발할 수 있다. 대피소별 입소 건수 분석이 필요한 경우 RDS 쿼리 또는 별도 집계 파이프라인을 사용한다.
> 

> `api_core_shelter_full_count`는 `GET /admin/dashboard` 처리 시 RDS를 직접 조회하여 산출한 값으로 갱신한다. Redis가 아닌 RDS가 집계 원본이다.
> 

> `api_core_shelter_checkin_failed_total`의 `reason` label 허용 값: `SHELTER_FULL`
> 

---

# 15. 향후 확장 후보 (현 시점 미도입)

- Refresh Token
- `GET /admin/evacuation-entries/{entryId}` 단건 상세 조회 API
- `GET /shelters/nearby` 최대 반환 건수 확정
- paging 도입
- worker별 이벤트 스키마 버전 고도화