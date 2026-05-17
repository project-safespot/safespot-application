# SafeSpot REST API - api-public-read

이 문서는 `api-public-read` API 계약을 정의한다.

공통 auth, response, error, enum, validation 규칙은 `docs/api/api-common.md`를 따른다.

## 1. 책임

`api-public-read`가 소유한다:

- public shelter read
- public disaster read
- public weather 및 air-quality read
- public shelter map tile read
- Redis-first read
- cache regeneration request event
- 현재 구현이 Redis에서 응답할 수 없을 때의 임시 degraded-mode fallback 처리

소유하지 않는다:

- admin write
- authentication issuance
- Redis rebuild 실행
- external ingestion

## 2. 현재 구현 vs 목표 상태

현재 구현:

- read path는 Redis first다.
- 일부 miss 또는 parse failure는 임시 degraded-mode escape hatch로 RDS fallback을 사용할 수 있다.
- cache regeneration event는 현재 문서화되어 있지만, 일부 key의 worker-side regeneration은 아직 stub일 수 있다.

목표 아키텍처:

- regeneration request는 `EVENT-007`을 통해 흐른다.
- 일반 public hot path는 RDS에 의존하지 않는다.
- `/shelters/nearby`와 `/shelters/map/tiles`는 Redis GEO / tile index 기반으로 동작한다.
- nearby/map hot path에서 RDS 후보 조회는 사용하지 않는다.
- worker가 요청된 cache entry를 rebuild한다.

## 3. Seoul MVP 검증

현재 MVP 범위는 서울만 해당한다.

- non-Seoul `region` -> `400 UNSUPPORTED_REGION`
- valid `lat` / `lng` outside Seoul -> `400 UNSUPPORTED_REGION`
- invalid `lat` / `lng` format -> `400 VALIDATION_ERROR`

## 4. Cache Model

### 4.1 Disaster cache

Canonical disaster message read model:

- `disaster:messages:recent:seoul`
- `disaster:message:core:seoul`
- `disaster:messages:list:seoul`
- `disaster:detail:{alertId}`

Endpoint 및 screen mapping:

| Consumer 또는 screen | Redis key | 참고 |
| --- | --- | --- |
| disaster overview recent messages | `disaster:messages:recent:seoul` | Top 5 recent in-scope messages |
| global 또는 menu core message | `disaster:message:core:seoul` | 단일 core message |
| disaster message page list | `disaster:messages:list:seoul` | Top N만 저장, default `50` |
| disaster message detail | `disaster:detail:{alertId}` | 단일 detail payload |

규칙:

- `api-public-read`는 Redis-first다.
- disaster read model은 이 service가 아니라 `async-worker`가 rebuild한다.
- `disasterType`과 `messageCategory`는 payload field이며 Redis key dimension이 아니다.
- district는 MVP disaster message list의 Redis key dimension이 아니다.
- disaster message list read의 filtering은 payload 기반이다.
- `disaster:messages:list:seoul`은 Top N만 저장하며 default는 `50`이다.
- RDS는 full history를 저장하며 원천 데이터로 남는다.
- 일반 public read path는 RDS에 의존하면 안 된다.
- direct RDS fallback은 현재 구현이 Redis에서 응답할 수 없을 때만 사용하는 임시 degraded-mode이다.

Miss handling:

- recent miss -> recent regeneration request publish
- core miss -> core regeneration request publish
- list miss -> list regeneration request publish
- detail miss -> detail regeneration request publish

### 4.2 Shelter cache

Canonical shelter read models:

- `shelter:detail:{shelterId}`
- `shelter:geo:seoul:{disasterType}:{shelterType}`
- `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`
- `shelter:map:item:{shelterId}`
- `shelter:status:{shelterId}`

규칙:

- `shelter:geo`는 nearby의 필수 위치 index다.
- `shelter:map:tile`은 map viewport용 tile index다.
- `shelter:map:item`은 정적 marker/item payload다.
- `shelter:status`는 동적 overlay payload다.
- `shelter:list:*`는 active contract가 아니다.

`GET /shelters/{shelterId}`는 `shelter:detail:{shelterId}`를 우선 조회하고, miss/down/parse error에서만 singleflight + 짧은 memoization으로 DB fallback을 사용한다.

### 4.3 Cache regeneration

현재 구현:

- regeneration request behavior는 API 계약 수준에 존재한다.
- 일부 regeneration flow는 key family에 따라 stub으로 남아 있다.

목표 아키텍처:

- `EVENT-007`이 worker rebuild를 구동한다.
- `api-public-read`는 Redis SET으로 rebuild하지 않는다.
- `api-public-read`는 `CacheRegenerationRequested`만 publish한다.

## 5. Endpoints

### 5.1 GET /shelters/nearby

목적: 현재 위치 기준 가까운 shelter 목록을 반환한다.

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `lat` | number | Y | valid latitude |
| `lng` | number | Y | valid longitude |
| `radius` | number | Y | `100` to `5000` |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |
| `shelterType` | string | N | `DESIGNATED` / `TEMPORARY` / `WIDE` |
| `limit` | number | N | default `50`, max `50` |

동작:

- list 전용 API다.
- `disasterType` 또는 `shelterType` 필터를 적용한 뒤 가까운 순으로 최대 `50`개를 반환한다.
- Redis GEO index에서 후보 shelterId를 얻는다.
- `shelter:map:item:{shelterId}`로 정적 marker/item 정보를 읽는다.
- 필요하면 `shelter:status:{shelterId}`로 동적 상태를 overlay한다.
- hot path에서 RDS 후보 조회를 수행하면 안 된다.
- 통합 nearby 결과를 받은 뒤 프론트에서 `disasterType` 또는 `shelterType`를 다시 필터링하는 구조가 아니다.
- `disasterType` 또는 `shelterType` 변경 시 프론트는 API를 다시 호출해야 한다.

`200` 응답:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "shelterId": 101,
        "shelterName": "Mapo Gymnasium Shelter",
        "shelterType": "DESIGNATED",
        "disasterType": "EARTHQUAKE",
        "address": "Seoul Mapo-gu ...",
        "latitude": 37.5687,
        "longitude": 126.9081,
        "distanceM": 420,
        "currentOccupancy": 128,
        "availableCapacity": 0,
        "congestionLevel": "FULL",
        "shelterStatus": "OPERATING",
        "updatedAt": "2026-04-14T09:10:00+09:00"
      }
    ]
  }
}
```

참고:

- `congestionLevel`은 informational only다.
- `FULL`은 admission을 막지 않는다.
- `limit`은 결과 상한이며 50을 초과하면 validation error로 처리한다.
- `radius`는 검색 반경 상한일 뿐이며, nearby 결과는 `limit` 기준으로 잘린다.
- `shelterType` 또는 `disasterType`을 바꾸면 재조회가 필요하다.

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 필수 필드 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 형식/범위 | 400 | `VALIDATION_ERROR` |
| 서울 밖 | 400 | `UNSUPPORTED_REGION` |

### 5.2 GET /shelters/map/tiles

목적: 지도 viewport에 필요한 tile별 shelter marker를 반환한다.

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `z` | number | Y | `11` to `16` 우선 권장 |
| `tiles` | string | Y | `x:y` 목록, 예: `7285:3172,7285:3173` |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |
| `shelterType` | string | N | `DESIGNATED` / `TEMPORARY` / `WIDE` |

동작:

- map 전용 API다.
- nearby `50`개 제한과 독립적으로 동작한다.
- bbox query가 아니라 tile/grid index를 사용한다.
- 프론트는 지도 idle/moveend 시 필요한 tile 목록을 재계산해 호출한다.
- `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`를 읽고, 필요하면 `shelter:map:item:{shelterId}`와 `shelter:status:{shelterId}`를 overlay한다.
- 응답은 static marker 중심이어야 한다.
- `50`개 제한은 적용하지 않는다.
- 서버 구현에서는 안전 cap, zoom policy, unsupported zoom 응답을 둘 수 있다.

`200` 응답:

```json
{
  "success": true,
  "data": {
    "z": 13,
    "tiles": [
      {
        "x": 7285,
        "y": 3172,
        "items": [
          {
            "shelterId": 101,
            "shelterName": "Mapo Gymnasium Shelter",
            "shelterType": "DESIGNATED",
            "disasterType": "FLOOD",
            "address": "Seoul Mapo-gu ...",
            "latitude": 37.5687,
            "longitude": 126.9081,
            "currentOccupancy": 128,
            "availableCapacity": 0,
            "congestionLevel": "FULL",
            "shelterStatus": "OPERATING",
            "updatedAt": "2026-04-14T09:10:00+09:00"
          }
        ]
      }
    ],
    "degraded": false,
    "truncated": false
  }
}
```

참고:

- `z < 11`은 unsupported, bounded response, 또는 확대 필요 응답으로 처리할 수 있다.
- CDN/API cache 재사용성을 위해 같은 `z/x/y/disasterType/shelterType` 조합은 안정적인 query key를 유지해야 한다.
- `disasterType` 또는 `shelterType` 변경 시 프론트는 API를 다시 호출해야 한다.

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 필수 필드 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 형식/범위 | 400 | `VALIDATION_ERROR` |
| unsupported zoom | 400 또는 422 | `UNSUPPORTED_ZOOM` |

### 5.3 GET /shelters/{shelterId}

`200` 응답:

```json
{
  "success": true,
  "data": {
    "shelterId": 101,
    "shelterName": "Mapo Gymnasium Shelter",
    "disasterType": "EARTHQUAKE",
    "address": "Seoul Mapo-gu ...",
    "latitude": 37.5687,
    "longitude": 126.9081,
    "capacityTotal": 120,
    "currentOccupancy": 128,
    "availableCapacity": 0,
    "congestionLevel": "FULL",
    "shelterStatus": "OPERATING",
    "manager": "Kim Admin",
    "contact": "02-123-4567",
    "note": "Basement level 1",
    "updatedAt": "2026-04-14T09:10:00+09:00"
  }
}
```

### 5.4 GET /disaster-alerts

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | 서울만 허용 |
| `disasterType` | string | N | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |

`200` 응답:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "alertId": 55,
        "disasterType": "FLOOD",
        "region": "Seoul",
        "level": "WARNING",
        "message": "River level warning",
        "issuedAt": "2026-04-14T08:55:00+09:00",
        "expiredAt": null
      }
    ]
  }
}
```

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| 유효하지 않은 enum | 400 | `VALIDATION_ERROR` |
| 서울 밖 | 400 | `UNSUPPORTED_REGION` |

Redis read model 참조:

- primary key: `disaster:messages:list:seoul`
- `disasterType` filtering은 payload item에 적용한다.
- `messageCategory`, `level`, `rawType`도 payload field에서 filtering할 수 있다.
- 이 key는 Top N read model이며 default는 `50`이고 full history가 아니다.
- RDS는 full disaster message history를 저장한다.

### 5.5 GET /disasters/{disasterType}/latest

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | Y | 서울만 허용 |

동작:

- disaster latest-style read는 `{disasterType}` pointer key가 아니라 canonical disaster message read model을 사용해야 한다.
- selection은 `disaster:messages:list:seoul` 또는 `disaster:messages:recent:seoul`의 payload field에서 수행한다.
- detail expansion은 `disaster:detail:{alertId}`를 사용한다.
- MVP disaster message cache의 Redis key dimension으로 `{disasterType}`을 도입하지 않는다.

`200` 응답:

```json
{
  "success": true,
  "data": {
    "alertId": 55,
    "disasterType": "EARTHQUAKE",
    "region": "Seoul",
    "level": "WARNING",
    "message": "Seoul earthquake warning",
    "issuedAt": "2026-04-14T08:55:00+09:00",
    "expiredAt": null,
    "details": {
      "magnitude": 4.3,
      "epicenter": "North Gyeonggi",
      "intensity": "IV"
    }
  }
}
```

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| `region` 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 enum | 400 | `VALIDATION_ERROR` |
| 서울 밖 | 400 | `UNSUPPORTED_REGION` |
| 찾을 수 없음 | 404 | `NOT_FOUND` |

### 5.6 GET /weather-alerts

지원 input:

- region-only 서울 lookup
- `nx` / `ny`를 사용하는 optional grid lookup

Weather API 규칙:

- 서울만 허용하는 region validation을 적용한다.
- `nx` / `ny`가 제공되면 valid grid coordinate여야 한다.
- region input은 Seoul grid로 mapping된다.

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | 서울만 허용 |
| `nx` | number | N | valid grid x |
| `ny` | number | N | valid grid y |

`region` 또는 `nx` + `ny` 중 하나 이상을 제공해야 한다.

`200` 응답:

```json
{
  "success": true,
  "data": {
    "region": "Seoul",
    "nx": 60,
	    "ny": 127,
	    "temperature": 18.5,
	    "weatherCondition": "관측값",
	    "precipitationType": "없음",
	    "precipitation": "0",
	    "windSpeed": 1.2,
	    "humidity": 65,
	    "forecastedAt": "2026-04-15T15:00:00+09:00"
	  }
	}
```

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| selector 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 유효하지 않은 `nx` / `ny` | 400 | `VALIDATION_ERROR` |
| 서울 밖 | 400 | `UNSUPPORTED_REGION` |

Redis read model 참조:

- primary weather-alert key: `environment:weather-alert:seoul`
- weather context key: `environment:weather:seoul`
- environment key는 `env:*`가 아니라 `environment:*`를 사용한다.

### 5.7 GET /air-quality

Query parameter:

| Parameter | Type | Required | Rule |
| --- | --- | --- | --- |
| `region` | string | N | 서울만 허용 |
| `stationName` | string | N | Seoul station name |

`region` 또는 `stationName` 중 하나 이상을 제공해야 한다.

`200` 응답:

```json
{
  "success": true,
  "data": {
    "stationName": "중구",
    "aqi": 42,
    "grade": "좋음",
    "pm10": 34,
    "pm10Grade": "좋음",
    "pm25": 18,
    "pm25Grade": "보통",
    "o3": 0.025,
    "o3Grade": "좋음",
    "measuredAt": "2026-04-15T15:00:00+09:00"
  }
}
```

실패:

| Case | HTTP | Code |
| --- | --- | --- |
| selector 누락 | 400 | `MISSING_REQUIRED_FIELD` |
| 서울 밖 | 400 | `UNSUPPORTED_REGION` |

Redis read model 참조:

- primary key: `environment:air-quality:seoul`
- environment key는 `env:*`가 아니라 `environment:*`를 사용한다.

## 6. Fallback 및 Regeneration 규칙

- Redis hit -> cached value 반환
- Redis miss/stale/parse failure -> suppress window에 따라 regeneration request publish
- `/shelters/nearby`와 `/shelters/map/tiles`의 target hot path는 RDS 후보 조회를 사용하지 않는다.
- `shelter:geo:seoul:*` miss 또는 empty는 degraded 또는 `503` 정책과 regeneration request로 처리한다.
- 현재 구현이 Redis에서 응답할 수 없으면 일부 비주요 path에서만 임시 degraded-mode fallback을 사용할 수 있다.
- degraded-mode fallback은 target hot-path behavior가 아니다.

`EVENT-007` status:

- current: 계약은 문서화되어 있으며 일부 regeneration path는 아직 stub일 수 있다.
- target: worker가 요청을 수신하고 요청된 cache entry를 rebuild한다.

Disaster cache regeneration 규칙:

- `api-public-read`는 `CacheRegenerationRequested`를 publish할 수 있다.
- `api-public-read`는 read model을 직접 rebuild하기 위해 Redis `SET`을 호출하면 안 된다.
- `disaster:messages:recent:seoul`, `disaster:message:core:seoul`, `disaster:messages:list:seoul`, `disaster:detail:{alertId}`의 rebuild는 `async-worker`가 소유한다.
- RDS가 원천 데이터로 남더라도 일반 hot-path read는 RDS에 의존하면 안 된다.

Shelter cache regeneration 규칙:

- `api-public-read`는 shelter cache target에 대해 `CacheRegenerationRequested`만 publish한다.
- `api-public-read`는 shelter cache를 직접 rebuild하기 위해 Redis `SET` 또는 GEO write를 호출하면 안 된다.
- `shelter:status:{shelterId}`, `shelter:map:item:{shelterId}`, `shelter:geo:seoul:{disasterType}:{shelterType}`, `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`의 rebuild는 `async-worker`가 소유한다.
- `nearby`와 `map` hot path는 RDS candidate lookup으로 복구하지 않는다.

## 7. 관련 문서

- common API rules: `docs/api/api-common.md`
- event envelope: `docs/event/event-envelope.md`
- worker behavior: `docs/event/async-worker.md`
- Redis keys: `docs/redis-key/redis-key.md`
