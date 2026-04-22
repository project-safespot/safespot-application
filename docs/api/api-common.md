# SafeSpot REST API 공통 명세

> 본 문서는 SafeSpot REST API의 공통 기준, 인증 정책, 공통 스키마를 정의한다.
> api-core.md 및 api-public-read.md는 본 문서를 기준으로 한다.
> 현재 프로젝트에서 합의된 최신 기준을 우선 반영하며, 기존 문서와 충돌 시 본 문서를 구현 기준으로 사용한다.

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
- 워크로드 기준 정의
- 인증 정책
- 공통 응답 구조
- 공통 validation / error 규칙
- 보안 / 로그 / 개인정보 주의사항
- 향후 확장 후보

본 문서에는 아래가 포함되지 않는다.
- 개별 API 엔드포인트 명세 (api-core.md / api-public-read.md 참조)
- 이벤트 envelope 및 payload 상세 (docs/event/event-envelope.md 참조)
- worker 내부 처리 로직 (docs/async/async-worker.md 참조)
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
- Redis miss 시 캐시 재생성 요청 이벤트 발행 (suppress window 적용)

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

## 11. 이벤트 명세

이벤트 envelope 및 payload 전문은 `docs/event/event-envelope.md`를 참조한다.

---

## 13. 보안 / 로그 / 개인정보 주의사항

### 13.1 저장 금지 / 노출 금지

- `password_hash` 응답 금지
- `rrn_front_6` 응답 금지
- 위치 정보(`lat`, `lng`) 저장 금지
- 공개 조회 응답에 개인정보 포함 금지

### 13.2 로그 금지

애플리케이션 로그에 아래 직접 기록 금지:
- 이름
- 전화번호
- 주소
- 건강상태
- 가족관계
- 비밀번호
- 주민번호 관련 값

### 13.3 관리자 감사 로그

- `payload_before`, `payload_after` 유지
- append-only
- 관리자도 직접 수정 불가

---

## 15. 향후 확장 후보 (현 시점 미도입)

- Refresh Token
- `GET /admin/evacuation-entries/{entryId}` 단건 상세 조회 API
- `GET /shelters/nearby` 최대 반환 건수 확정
- paging 도입
- worker별 이벤트 스키마 버전 고도화