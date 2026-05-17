# Redis Key 계약

이 문서는 Redis key name 및 cache model 규칙의 기준 문서다.

Redis는 derived read data만 저장한다. RDS는 원천 데이터로 남는다.

## 1. Region Namespace 규칙

현재 MVP 범위는 서울만 해당한다.

- MVP cache key의 canonical region namespace로 `seoul`을 사용한다.
- non-Seoul request는 MVP에서 새로운 live disaster message namespace를 생성하면 안 된다.
- 향후 regional expansion에서 region-specific namespace를 추가할 수 있지만, 현재 disaster message key는 Seoul-only로 유지한다.

## 2. Disaster Message Read Model

disaster message read model은 다음 canonical key를 사용한다.

| Key | 목적 | 참고 |
| --- | --- | --- |
| `disaster:messages:recent:seoul` | disaster status / overview page | `issuedAt DESC` 순서의 Top 5 recent in-scope message 저장 |
| `disaster:message:core:seoul` | global page 또는 menu highlight area | 가장 중요한 current message 1건 저장 |
| `disaster:messages:list:seoul` | disaster message page | `issuedAt DESC` 순서의 Top 50 recent in-scope message 저장 |
| `disaster:detail:{alertId}` | detail view 또는 expanded read model lookup | message detail payload 1건 저장 |

규칙:

- `disasterType`은 payload field이며 Redis key dimension이 아니다.
- `messageCategory`는 payload field이며 Redis key dimension이 아니다.
- `rawType`은 UI 표시와 auditability를 위해 payload field로 보존한다.
- Redis list cache는 bounded read model이며 full history가 아니다.
- RDS는 full history와 원천 데이터로 남는다.

### 2.1 공통 Payload 계약

모든 disaster message read model payload item은 최소한 다음을 포함해야 한다.

- `schemaVersion`
- `alertId`
- `disasterType`
- `rawType`
- `messageCategory`
- `level`
- `levelRank`
- `region`
- `issuedAt`

권장 additional field:

- `title`
- `message`
- `source`
- `sourceRegion`
- `rawLevel`
- `rawLevelTokens`
- `rawCategoryTokens`
- `isInScope`
- `normalizationReason`

`schemaVersion`은 필수이며 `1`이어야 한다.

### 2.2 `disaster:messages:recent:seoul`

목적:

- disaster status 또는 overview page에서 사용한다.
- weather 및 air-quality context와 함께 render될 수 있지만, key 자체는 disaster message만 저장한다.

규칙:

- Seoul MVP만 해당
- Top 5 recent in-scope messages
- `issuedAt DESC`로 정렬

### 2.3 `disaster:message:core:seoul`

목적:

- global page 또는 menu highlight area에서 사용한다.

Selection rule:

- `isInScope = true`
- `levelRank >= 3`
- `messageCategory != CLEAR`
- order by `issuedAt DESC`
- limit `1`

Fallback behavior:

- matching message가 없으면 `null` 또는 `schemaVersion = 1`인 empty payload wrapper를 저장한다.
- caller는 이를 "현재 core disaster message 없음"으로 처리해야 한다.

### 2.4 `disaster:messages:list:seoul`

목적:

- disaster message page에서 사용한다.
- payload field 기반 client-side filtering을 지원한다.

Payload filter field:

- `disasterType`
- `messageCategory`
- `level`
- `rawType`

규칙:

- Top `50`만 저장
- full history가 아니다.
- `issuedAt DESC`로 정렬

### 2.5 `disaster:detail:{alertId}`

목적:

- disaster message detail payload 1건을 저장한다.
- detail view 또는 list/recent payload에서의 detail expansion에 사용한다.

## 3. Retired Disaster Keys

다음 key는 retired 상태이며 현재 계약 target으로 사용하면 안 된다.

- `disaster:active`
- `disaster:latest:{disasterType}:{region}`
- `disaster:latest:*`
- `disaster:alert:list`
- `disaster:messages:list:seoul:{type}`
- `disaster:messages:list:seoul:{type}:{category}`
- `disaster:messages:list:seoul:{district}:*`

정책:

- active concept는 MVP에서 구현하지 않는다.
- latest pointer는 recent/core 구분으로 대체한다.
- `alert:list`는 `disaster:messages:list:seoul`로 대체한다.
- type, category, district는 payload 또는 future-scope concept이며 MVP key dimension이 아니다.

## 4. Environment Keys

environment key는 disaster message key와 분리되어 유지된다.

| Key | 목적 |
| --- | --- |
| `environment:weather:seoul` | Seoul MVP weather forecast read model |
| `environment:air-quality:seoul` | Seoul MVP air-quality read model |
| `environment:weather-alert:seoul` | Seoul MVP weather-alert read model |

규칙:

- `disaster:messages:*`는 disaster message read model에만 사용한다.
- `environment:*`는 weather, air quality, weather alert style environment data에만 사용한다.
- `env:*`는 deprecated historical naming이며 현재 Redis 계약으로 사용하면 안 된다.
- environment payload를 disaster message key family에 섞지 않는다.
- `environment:weather:seoul` payload는 temperature 외에 precipitation, wind, humidity context를 포함할 수 있다.
- `environment:air-quality:seoul` payload는 CAI 외에 PM10, PM2.5, O3 context를 포함할 수 있다.

## 5. Shelter Key Contract

이 문서는 shelter key를 최신 read model 계약으로 정의한다.

현재 shelter key family는 다음을 사용한다.

- `shelter:geo:seoul:{disasterType}:{shelterType}`
- `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`
- `shelter:map:item:{shelterId}`
- `shelter:status:{shelterId}`

규칙:

- MVP region namespace는 `seoul`이다.
- `disasterType` canonical enum은 `EARTHQUAKE`, `FLOOD`, `LANDSLIDE`다.
- `shelterType` canonical enum은 `DESIGNATED`, `TEMPORARY`, `WIDE`다.
- key dimension에 한글 표시값을 사용하면 안 된다.
- unspecified dimension이 필요하면 `all`을 사용할 수 있다.
- `shelter:geo`는 일반 response cache가 아니라 nearby read path의 필수 GEO index다.
- GEO member는 `shelterId`다.
- GEO coordinate 순서는 longitude, latitude다.
- 정상 운영 상태에서 GEO key miss 또는 empty는 없어야 한다.
- `shelter:status`는 동적 상태 read model이다.
- `shelter:map:item`은 정적 marker/item read model이다.
- `shelter:map:tile`은 tile-scoped marker reference read model이다.
- legacy `shelter:list:*`는 retired 또는 historical warning 이외의 active contract로 사용하면 안 된다.

예:

- `shelter:geo:seoul:all:all`
- `shelter:geo:seoul:FLOOD:all`
- `shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED`

### 5.1 `shelter:map:item:{shelterId}`

목적:

- map marker 렌더링에 필요한 정적 shelter item을 저장한다.

최소 payload field:

- `schemaVersion`
- `shelterId`
- `shelterName`
- `shelterType`
- `disasterType`
- `address`
- `latitude`
- `longitude`
- `updatedAt`

규칙:

- 정적 marker 정보만 저장한다.
- 동적 상태는 `shelter:status:{shelterId}`로 분리한다.
- CDN/API cache reuse를 위해 stable payload를 유지한다.

### 5.2 `shelter:status:{shelterId}`

목적:

- 현재 인원, 가용 용량, 혼잡도, 운영 상태를 저장한다.

동적 payload field:

- `currentOccupancy`
- `availableCapacity`
- `congestionLevel`
- `shelterStatus`

규칙:

- 동적 상태만 저장한다.
- `shelter:map:item`의 정적 marker payload와 분리한다.

### 5.3 `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`

목적:

- `/shelters/map/tiles`에서 viewport tile 단위 shelter 후보를 제공한다.

규칙:

- payload는 `shelterId` 목록 또는 marker item ref 목록이다.
- 같은 `z/x/y/disasterType/shelterType` 조합은 CDN/API cache 재사용 가능해야 한다.
- bbox query를 key dimension으로 사용하면 안 된다.
- 1차 권장 zoom 범위는 `z=11..16`이다.
- `z<11`은 unsupported, bounded response, 또는 확대 필요 응답으로 처리할 수 있다.

### 5.4 `shelter:geo:seoul:{disasterType}:{shelterType}`

목적:

- `/shelters/nearby`의 필수 위치 index다.

규칙:

- Redis GEO index는 derived read model이다.
- RDS 후보 조회를 대체하는 hot path index다.
- normal operation에서 GEO miss는 없어야 한다.
- GEO miss는 개별 cache miss가 아니라 read model bootstrap 또는 rebuild 문제로 취급한다.
- GEO miss 시 RDS fallback을 사용하면 안 된다.
- GEO miss는 degraded 또는 503 정책과 `SHELTER_GEO_INDEX` regeneration request로 처리한다.

## 6. Miss Handling 규칙

- Redis hit -> cached value 반환
- Redis miss/down/parse error -> 필요 시 consuming service가 정의한 degraded-mode fallback behavior 사용
- degraded-mode fallback 또는 stale detection 후 suppress window 계약에 따라 `CacheRegenerationRequested` publish

Shelter key miss 규칙:

- `shelter:status:{shelterId}` miss -> `SHELTER_STATUS` regeneration 요청
- `shelter:map:item:{shelterId}` miss -> `SHELTER_MAP_ITEMS` batch regeneration 요청
- `shelter:geo:seoul:*` miss 또는 empty -> `SHELTER_GEO_INDEX` regeneration 요청
- `shelter:map:tile:*` miss -> `SHELTER_MAP_TILES` regeneration 요청
- parse error는 shelter 개수만큼 개별 이벤트를 fan-out하지 말고 batch collapse한다.

Disaster message miss 규칙:

- `disaster:messages:recent:seoul` miss -> recent rebuild 요청
- `disaster:message:core:seoul` miss -> core rebuild 요청
- `disaster:messages:list:seoul` miss -> list rebuild 요청
- `disaster:detail:{alertId}` miss -> detail rebuild 요청

## 7. Suppress Window Keys

suppress key는 실제 regeneration target key에서 파생해야 한다.

Format:

- `suppress:cache-regeneration:{cacheKeyHash}`

규칙:

- regeneration을 요청하는 정확한 cache key string을 hash한다.
- 서로 다른 cache family를 같은 suppress key로 collapse하지 않는다.
- suppress key는 duplicate-throttling guard일 뿐이며 read-model entry가 아니다.

Example:

- miss target: `disaster:messages:list:seoul`
- suppress target: `suppress:cache-regeneration:{hash("disaster:messages:list:seoul")}`

## 8. Endpoint To Redis Mapping

| Consumer 또는 page | Redis key |
| --- | --- |
| shelter nearby list | `shelter:geo:seoul:{disasterType}:{shelterType}`, `shelter:map:item:{shelterId}`, `shelter:status:{shelterId}` |
| shelter map tiles | `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`, `shelter:map:item:{shelterId}`, `shelter:status:{shelterId}` |
| disaster overview recent messages | `disaster:messages:recent:seoul` |
| global 또는 menu core message | `disaster:message:core:seoul` |
| disaster message page list | `disaster:messages:list:seoul` |
| disaster detail view | `disaster:detail:{alertId}` |
| shelter status | `shelter:status:{shelterId}` |

## 9. Worker Regeneration Target

shelter cache에 대한 `async-worker` rebuild target은 다음과 같다.

1. `shelter:status:{shelterId}`
2. `shelter:map:item:{shelterId}`
3. `shelter:geo:seoul:{disasterType}:{shelterType}`
4. `shelter:map:tile:{z}:{x}:{y}:{disasterType}:{shelterType}`

규칙:

- status와 map item은 `targetIds` 기반 batch regeneration을 우선한다.
- GEO index는 서울 전체 또는 필터 조합별 rebuild가 가능해야 한다.
- map tile index는 관련 tile batch 또는 전체 index rebuild가 가능해야 한다.
- 1차에서는 Seoul 전체 rebuild를 허용한다.
- RDS에서만 rebuild한다.
- worker에서 candidate lookup fallback을 수행하지 않는다.
- retired `shelter:list:*` key를 rebuild하지 않는다.

disaster message read model에 대한 `async-worker` rebuild target은 다음과 같다.

1. `disaster:detail:{alertId}`
2. `disaster:messages:recent:seoul`
3. `disaster:message:core:seoul`
4. `disaster:messages:list:seoul`

규칙:

- normalized DB data에서만 rebuild한다.
- worker에서 raw message를 reclassify하지 않는다.
- public disaster message read model에서 `isInScope = false` record를 제외한다.
- retired key를 rebuild하지 않는다.
- recent 또는 list rebuild 시 Top N policy를 적용한다.

## 10. Hot Key 및 Cardinality 참고

MVP는 disaster message key dimension에서 `disasterType`, `messageCategory`, district를 제거해 Redis key cardinality를 의도적으로 줄인다.

효과:

- regeneration 단순화
- hit ratio 개선
- key 수 감소
- memory overhead 감소

위험:

- `disaster:messages:list:seoul`가 hot key가 될 수 있다.
- payload filtering cost가 `api-public-read`로 이동한다.
- payload size는 bounded 상태를 유지해야 한다.

완화:

- Top N = `50`
- hot key metric을 monitor한다.
- payload size를 monitor한다.
- MVP trade-off가 더 이상 동작하지 않으면 future expansion에서 dimension을 추가할 수 있다.

## 11. Ownership 분리

- `api-core` = 즉시 제거가 필요한 stale shelter key를 `DEL`로 invalidate
- `api-public-read` = miss, stale detection, degraded-mode fallback 후 regeneration 요청
- `async-worker` = Redis read model rebuild
- `external-ingestion` = normalized DB data를 write하고 downstream rebuild flow를 trigger할 수 있지만 Redis에 직접 write하지 않음

## 12. 관련 문서

- public read behavior: `docs/api/api-public_read.md`
- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- TTL policy: `docs/redis-key/cache-ttl.md`
