    # SafeSpot REST API — api-public-read

> 본 문서는 `api-public-read` 워크로드의 API 엔드포인트 명세다.
> 공통 기준(인증 정책, 응답 구조, 에러 코드, Enum, Validation)은 `docs/api/api-common.md`를 참조한다.
> 이벤트 envelope 및 payload 전문은 `docs/event/event-envelope.md`를 참조한다.

---

## api-public-read 책임 범위

- 공개 shelter 조회
- 공개 재난 조회
- 보조 환경 조회
- Redis 우선 조회
- RDS fallback 처리
- Redis miss 시 캐시 재생성 요청 이벤트 발행 (`CacheRegenerationRequested`, suppress window 적용)

---

## 구현 시 책임 경계

handler/service 책임은 워크로드 기준으로 분리한다.
공통 DTO/enum/error schema는 공유 가능하지만, api-core의 handler/service 로직을 이 워크로드에 혼재시키지 않는다.

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
- 최대 반환 건수: TBD
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

---

## 9.4 GET /disasters/{disasterType}/latest

### 권한

없음

### 목적

재난 유형 + 지역(region) 기준 최신 재난 상세 1건을 반환한다.

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

---

# 12. 캐시 및 Fallback 정책

## 12.1 기본 원칙

- 공개 조회는 Redis 우선 조회
- Redis MISS 또는 Redis 장애 시 RDS fallback
- fallback 응답 자체는 즉시 반환
- fallback 이후 캐시 재생성 요청은 별도 이벤트로 전달

## 12.2 Suppress Window

- `api-public-read` 는 Redis MISS 또는 Redis 장애로 fallback 발생 시
- **API 인스턴스 로컬 suppress window 10초** 기준으로 동일 키당 1회만 `CacheRegenerationRequested` 이벤트를 발행한다
- suppress window 안의 추가 fallback은 이벤트를 재발행하지 않는다

## 12.3 적용 우선 대상

- `shelter:status:{shelterId}`
- `shelter:list:{shelterType}:{disasterType}`
- `disaster:alert:list:{region}:{disasterType}`
- `disaster:latest:{disasterType}:{region}`
- `disaster:detail:{alertId}`

## 12.4 이벤트 발행

Redis miss + suppress window 통과 시 `CacheRegenerationRequested` 이벤트를 발행한다.
payload 전문 및 worker 분기 처리는 `docs/event/event-envelope.md` EVENT-007 및 `docs/async/async-worker.md`를 참조한다.