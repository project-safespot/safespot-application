# External Ingestion

이 문서는 external collection, DB write 전 normalization, write 후 event publication에 대한 `external-ingestion` 계약을 정의한다.

## 1. 범위

현재 MVP 범위는 서울만 해당한다.

- MVP에서는 external collection과 normalization이 서울 데이터에 대해서만 동작한다.
- region-derived output은 서울 범위 안에 있어야 한다.

## 2. 현재 구현 vs 목표 상태

현재 구현:

- external source는 polling loop 또는 CronJob으로 수집한다.
- normalized data는 downstream read model rebuild 전에 RDS에 기록한다.
- disaster alert에는 raw 값과 canonical 값을 함께 저장한다.
- post-commit event가 이후 async processing을 trigger할 수 있지만, `external-ingestion`은 Redis read model을 직접 build하지 않는다.

목표 아키텍처:

- 동일한 범위가 유지되며, publication mechanism이 변경되더라도 durability와 replay 요구사항은 유지되어야 한다.

## 3. Disaster Message Normalization 계약

`SAFETY_DATA_ALERT` 및 동등한 disaster-message source는 DB 저장 전에 normalize해야 한다.

필수 flow:

- external disaster message
- `external-ingestion` normalizer
- DB는 raw + canonical 값을 저장한다.
- async-worker는 이후 normalized DB data에서 Redis read model을 build한다.
- `api-public-read`는 Redis만 읽고 disaster message를 다시 normalize하지 않는다.

### 3.1 지원 Canonical `disasterType`

SafeSpot MVP public-read 범위는 다음 canonical 값만 지원한다.

| Canonical value | In scope | Mapping basis |
| --- | --- | --- |
| `EARTHQUAKE` | Yes | `지진`, `여진`, `지진해일`, `규모`, `진도` |
| `LANDSLIDE` | Yes | `산사태`, `산사태취약지역`, `산림 인접`, `비탈면`, `지반 약화`, `사면 붕괴` |
| `FLOOD` | Yes | `호우`, `홍수`, `침수`, `하천 범람`, `하천 수위 상승`, `수문방류`, `태풍`, `폭풍해일`, `해수면 상승`, `저지대 침수` |

강풍 규칙:

- `강풍`이 `태풍`, `호우`, `침수`, `해안`, `저지대` 문맥과 함께 나오면 `FLOOD`
- 단독 `강풍`은 MVP public-read 범위 밖이다

호우 + 산사태 혼합 규칙:

- 강우 표현과 산사태 표현이 함께 있어도 주된 위험 대상이 산지, 사면, 산림 인접 지역, 산사태 취약지역, 산사태 대피소이면 `LANDSLIDE`

### 3.2 Out-Of-Scope Type 정책

다음 유형은 MVP public-read 범위 밖이다:

- 폭염, 한파
- 산불, 일반 화재
- 대기질
- 교통사고, 교통통제
- 실종자
- 정전
- 정정, 오보, 훈련
- 가축 질병
- 감염병
- 일반 안전 안내
- 단독 `강풍`

범위 밖 메시지는 현재 수집 설계가 요구하면 raw collection record로 저장할 수 있다. 다만 public disaster read model에는 포함되면 안 된다.

### 3.3 Canonical `messageCategory`

Canonical category는 다음 3개만 사용한다:

- `ALERT`
- `GUIDANCE`
- `CLEAR`

매핑 규칙:

| Category | Primary tokens |
| --- | --- |
| `ALERT` | `발령`, `발표`, `발효`, `예비특보`, `주의보`, `경보`, `위기경보`, `발생`, `위험`, `우려` |
| `GUIDANCE` | `대피`, `자제`, `금지`, `통제`, `우회`, `확인`, `준수`, `협조`, `유의` |
| `CLEAR` | `해제`, `정상화`, `복구`, `진화 완료`, `통제 해제`, `완료` |

우선순위 규칙:

- `ALERT` + `GUIDANCE` 혼합 메시지는 주 카테고리를 `ALERT`로 설정한다
- `CLEAR` + `GUIDANCE` 혼합 메시지는 주 카테고리를 `CLEAR`로 설정한다
- `CLEAR` 또는 `ALERT`가 우선되어도 잔여 안내 문구는 raw payload 또는 원문 필드에 유지한다

### 3.4 Canonical `level` / `levelRank`

Canonical level은 다음 4개만 사용한다:

| level | levelRank |
| --- | --- |
| `INTEREST` | `1` |
| `CAUTION` | `2` |
| `WARNING` | `3` |
| `CRITICAL` | `4` |

외부 메시지는 `주의보`, `경보`, `위기경보 관심/주의/경계/심각`, `홍수정보(심각)`, `규모`, `진도`, `강수량`, `수위`, `대피명령`, `즉시 대피` 등 서로 다른 심각도 체계를 사용할 수 있다.

정규화 규칙:

- raw severity 표현은 버리면 안 된다
- 정규화 결과는 canonical `level`과 `levelRank`를 함께 저장해야 한다
- 원문 severity는 `rawLevel` 또는 `rawLevelTokens`로 보존해야 한다
- source severity를 안전하게 매핑할 수 없으면 raw severity를 보존하고 canonical `level` / `levelRank`는 미해결 상태로 남겨야 한다

### 3.5 Raw + Canonical Storage 정책

재난 알림 정규화 결과는 raw 값과 canonical 값을 함께 저장해야 한다.

필수 의미 필드:

- `rawType`
- `disasterType`
- `rawCategoryTokens`
- `messageCategory`
- `rawLevel`
- `rawLevelTokens`
- `level`
- `levelRank`
- `region`
- `sourceRegion`
- `issuedAt`
- `isInScope`
- `normalizationReason`

필드명은 실제 스키마 스타일을 따를 수 있다. 다만 의미는 유지해야 한다.

저장 규칙:

- `rawType`은 UI 표시와 감사 추적에 사용한다
- `disasterType`은 필터링과 내부 로직에 사용한다
- `rawLevel` / `rawLevelTokens`는 원문 severity 표현을 보존한다
- `level` / `levelRank`는 이후 핵심 메시지 선택 로직의 입력이다
- `isInScope`는 public disaster read model 포함 여부를 제어한다
- `normalizationReason`은 매핑 또는 제외 근거를 설명한다

## 4. Weather 및 Air-Quality 계약

weather는 MVP에서 region-scoped이다.

- region input은 grid coordinate로 mapping된다.
- Seoul region -> Seoul grid mapping
- `nx` / `ny`는 source API 및 DB storage selector로 남는다.
- 현재 public Redis read model은 Seoul environment namespace를 사용한다: `environment:weather:seoul`, `environment:weather-alert:seoul`, `environment:air-quality:seoul`

## 5. Event Publication 계약

normalized data가 RDS에 commit된 후:

- DB commit 후 publish한다.
- publish는 durable해야 한다.
- log-only failure handling은 허용하지 않는다.
- replay 또는 failure-channel recovery를 위해 full envelope를 보존한다.

## 6. Observability

structured log 및 metric label에는 `queue_name`을 포함할 수 있지만:

- `queue_name`은 logical queue name이어야 한다.
- raw queue URL을 `queue_name`으로 log하거나 label에 넣으면 안 된다.

## 7. 책임 분리

`external-ingestion`이 소유한다:

- external API collection
- raw payload persistence
- disaster message의 keyword 및 token extraction
- canonical `disasterType` mapping
- canonical `messageCategory` mapping
- canonical `level` / `levelRank` mapping
- `isInScope` decision
- normalized RDS write
- post-commit event publication

`external-ingestion`이 소유하지 않는다:

- Redis read model rebuild
- direct Redis `SET`
- direct Redis `DEL`
- public read API
- public-read reclassification 또는 re-normalization
- worker retry / DLQ 실행

이후 단계 ownership:

- async-worker: normalized DB data에서 Redis read model을 build한다.
- api-public-read: Redis read model만 읽는다.

## 8. External API Contract

기준: 실제 신청한 외부 API 공식 페이지 원문.
공식 원문과 코드가 충돌하면 공식 원문을 우선한다.
실제 인증키 값은 기재하지 않으며 env var명만 사용한다.

### 8.1 공통 원칙

- 공식 원문과 코드가 충돌하면 공식 원문을 우선한다.
- 실제 API key는 문서, 로그, 테스트, raw payload에 저장하지 않는다.
- 인증값은 `{KEY}` 또는 env var명만 사용한다.
- HTTP source와 file source를 명확히 구분한다.
- 외부 API 호출 테스트는 배포 전 smoke 단계에서만 수행한다.

### 8.2 Source 목록

| source code              | provider | API        | type | enabled | key env                   |
|--------------------------|----------|------------|------|---------|---------------------------|
| SAFETY_DATA_ALERT        | 행정안전부    | 긴급재난문자     | HTTP | true    | SAFETY_DATA_ALERT_API_KEY |
| KMA_EARTHQUAKE           | 기상청      | 지진정보조회     | HTTP | true    | KMA_API_KEY               |
| FORESTRY_LANDSLIDE       | 산림청      | 산사태 예측     | HTTP | false   | FORESTRY_API_KEY          |
| SEOUL_RIVER_LEVEL        | 서울시      | 하천 수위      | HTTP | true    | SEOUL_API_KEY             |
| SEOUL_EARTHQUAKE         | 서울시      | 지진 발생 현황   | HTTP | true    | SEOUL_API_KEY             |
| SEOUL_SHELTER_EARTHQUAKE | 서울시      | 지진 대피소     | HTTP | true    | SEOUL_API_KEY             |
| SEOUL_SHELTER_LANDSLIDE  | 서울시      | 산사태 대피소    | HTTP | true    | ODCLOUD_API_KEY           |
| SEOUL_SHELTER_FLOOD      | 서울시      | 수해 대피소     | FILE | false   | 없음                        |
| KMA_WEATHER              | 기상청      | 초단기실황      | HTTP | true    | KMA_API_KEY               |
| AIR_KOREA_AIR_QUALITY    | 환경공단     | 대기질 실시간 측정 | HTTP | true    | AIR_KOREA_API_KEY         |

참고:
- `SEOUL_API_KEY` 환경변수는 `SEOUL_EARTHQUAKE`, `SEOUL_RIVER_LEVEL`, `SEOUL_SHELTER_EARTHQUAKE` 3개 source가 공유한다.
- `KMA_API_KEY` 환경변수는 `KMA_EARTHQUAKE`와 `KMA_WEATHER` 2개 source가 공유한다.
- `ODCLOUD_API_KEY` 환경변수는 `SEOUL_SHELTER_LANDSLIDE` 전용이다. odcloud는 서울 열린데이터광장과 별도 provider이며 인증키가 다르다.

### 8.3 인증 방식 분류

#### 8.3.1 Query 인증 API

대상: `SAFETY_DATA_ALERT`, `KMA_EARTHQUAKE`, `FORESTRY_LANDSLIDE`, `KMA_WEATHER`, `AIR_KOREA_AIR_QUALITY`

규칙:
- 인증 위치: query parameter
- key 이름: `serviceKey` 또는 `ServiceKey` — source별 공식 계약의 대소문자를 그대로 사용한다
- URI 형태: `{endpoint}?serviceKey={KEY}&...`

#### 8.3.2 서울 OpenAPI (Path 인증)

대상: `SEOUL_RIVER_LEVEL`, `SEOUL_EARTHQUAKE`, `SEOUL_SHELTER_EARTHQUAKE`

규칙:
- 인증 위치: path segment
- URI 구조: `http://openapi.seoul.go.kr:8088/{KEY}/{TYPE}/{SERVICE}/{START_INDEX}/{END_INDEX}/`
- KEY를 query param으로 보내면 안 된다.
- TYPE은 `json` 또는 `xml`
- SERVICE는 공식 서비스명 그대로 사용한다.
- 서울 OpenAPI 계열은 query param(`KEY`, `Type`, `pIndex`, `pSize`) 방식이 아닌 **path KEY 방식**으로만 호출한다.

#### 8.3.3 File Source

대상: `SEOUL_SHELTER_FLOOD`

규칙:
- HTTP 호출 없음
- scheduler polling 없음
- 파일 ingest 전용

### 8.4 Source별 상세 계약

| source | provider | URL pattern | auth location | required params / path segments | daily quota | polling interval | enabled default | response root | verification status |
|---|---|---|---|---|---|---|---|---|---|
| `SAFETY_DATA_ALERT` | 행정안전부 SafetyData | `https://www.safetydata.go.kr/V2/api/DSSP-IF-00247` | query `serviceKey` (env: `SAFETY_DATA_ALERT_API_KEY`) | `pageNo`, `numOfRows`, `returnType=json` | 1,000/day | 2분 | true | `response.body.items.item[]` | TODO: V2 응답 구조 실계정 확인 필요 |
| `KMA_EARTHQUAKE` | 기상청 / data.go.kr | `https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg` | query `ServiceKey` (env: `KMA_API_KEY`) | `pageNo`, `numOfRows`, `dataType=JSON` | 10,000/day | 1분 | true | `response.body.items.item[]` | code-inferred. 공식 원문은 `http://` — 구현은 `https://` 사용 |
| `SEOUL_EARTHQUAKE` | 서울 열린데이터 | `http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/{start}/{end}` | path segment `{KEY}` (env: `SEOUL_API_KEY`) | start=1, end=20 (path) | 없음 | 1일 1회 (매일 06:00, CronJob) | true | `TbEqkKenvinfo.row[]` | TODO: 필드명 미확인 |
| `SEOUL_RIVER_LEVEL` | 서울 열린데이터 | `http://openapi.seoul.go.kr:8088/{KEY}/json/ListRiverStageService/{start}/{end}` | path segment `{KEY}` (env: `SEOUL_API_KEY`) | start=1, end=50 (path) | 없음 | 10분 | true | `ListRiverStageService.row[]` | TODO: 필드명 미확인 |
| `KMA_WEATHER` | 기상청 초단기실황 / data.go.kr | `https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst` | query `ServiceKey` (env: `KMA_API_KEY`) | `pageNo`, `numOfRows`, `dataType=JSON`, `base_date`, `base_time`, `nx`, `ny` | 10,000/day | 매시 정각 | true | `response.body.items.item[]` | code-inferred |
| `AIR_KOREA_AIR_QUALITY` | AirKorea / data.go.kr | `https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty` | query `serviceKey` (env: `AIR_KOREA_API_KEY`) | `returnType=json`, `numOfRows`, `pageNo`, `sidoName=서울`, `ver=1.0` | 500/day | 매시 정각 | true | `response.body.items[]` | smoke OK — 실계정 동작 확인. 초기 계약 문서의 `getMinuDustFrcstDspth`는 오류였음 |
| `SEOUL_SHELTER_EARTHQUAKE` | 서울 열린데이터 | `http://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/{start}/{end}` | path segment `{KEY}` (env: `SEOUL_API_KEY`) | start=1, end=1000 (path) | 없음 | 매일 02:00 (CronJob) | true | `TlEtqkP.row[]` | TODO: 필드명 미확인 |
| `SEOUL_SHELTER_LANDSLIDE` | odcloud (공공데이터포털) | `https://api.odcloud.kr/api/15118898/v1/uddi:19815091-0f2c-4d7a-a77f-96cec77038ad` | query `serviceKey` (env: `ODCLOUD_API_KEY`) | `page=1`, `perPage=1000`, `returnType=json` | 없음 | 매일 02:00 (CronJob) | true | `data[]` | TODO: odcloud 실계정 응답 필드명 확인 필요. 현재 normalizer 필드명은 미검증 |
| `FORESTRY_LANDSLIDE` | 산림청 / data.go.kr | ⚠️ 불일치 — 아래 상세 참조 | query `ServiceKey` (공식 원문) / `serviceKey` (현재 코드) — 불일치 | `pageNo`, `numOfRows`, `dataType=JSON` | 10,000/day | 5분 | **false** (승인 대기 중) | `response.body.items.item[]` | TODO: 승인 완료 후 공식 원문 기준으로 검증 필요 |
| `SEOUL_SHELTER_FLOOD` | 서울 열린데이터 | 파일 데이터 (xlsx) | 해당 없음 | 해당 없음 | 해당 없음 | 배치 전용 | false (batch-only placeholder) | 해당 없음 | TODO: 파일 파싱 구현 전 |

`code-inferred`: 실계정으로 실제 호출 검증을 완료하지 않았으며 코드에서 추론한 계약이다.

#### SAFETY_DATA_ALERT

- endpoint: `https://www.safetydata.go.kr/V2/api/DSSP-IF-00247`
- auth: query `serviceKey`
- 필수: `serviceKey`
- 선택: `numOfRows`, `pageNo`, `returnType`
- format: JSON/XML
- polling: 2분 (개발계정 1,000회/일 한도 — 720회/일)

#### KMA_EARTHQUAKE

- endpoint: `https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg`
  - 공식 원문은 `http://`로 표기하나 구현은 `https://`를 사용한다.
- auth: query `ServiceKey`
- 필수: `ServiceKey`, `pageNo`, `numOfRows`
- 선택: `dataType`
- root: `response.body.items.item`
- quota: 10,000/day

#### FORESTRY_LANDSLIDE

- 공식 원문 endpoint: `http://apis.data.go.kr/1400000/predictionInfoService/predictionInfoList`
- 현재 코드 endpoint: `https://apis.data.go.kr/1400119/slfswarnApi/getSlfswarnDataList`
- **⚠️ 불일치: 공식 원문과 코드의 endpoint가 다르다. 승인 완료 후 공식 원문 기준으로 반드시 검증 및 정렬 필요.**
- 공식 원문 auth: query `ServiceKey`
- 현재 코드 auth: query `serviceKey` — 대소문자 불일치
- 필수 (공식 원문 기준): `ServiceKey`
- 선택: `pageNo`, `numOfRows`
- root: `response.body.items.item`
- enabled: false (인증키 승인 대기 중)

#### SEOUL_RIVER_LEVEL

- endpoint pattern: `http://openapi.seoul.go.kr:8088/{KEY}/json/ListRiverStageService/{START_INDEX}/{END_INDEX}/`
- auth: path KEY (env: `SEOUL_API_KEY`)
- root: `ListRiverStageService.row`
- polling: 10분

#### SEOUL_EARTHQUAKE

- endpoint pattern: `http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/{START_INDEX}/{END_INDEX}/`
- auth: path KEY (env: `SEOUL_API_KEY`)
- root: `TbEqkKenvinfo.row`
- polling: 1일 1회 (매일 06:00, CronJob)

#### SEOUL_SHELTER_EARTHQUAKE

- endpoint pattern: `http://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/{START_INDEX}/{END_INDEX}/`
- auth: path KEY (env: `SEOUL_API_KEY`)
- root: `TlEtqkP.row`
- polling: 1일 1회 (매일 02:00, CronJob)

#### SEOUL_SHELTER_LANDSLIDE

- endpoint: `https://api.odcloud.kr/api/15118898/v1/uddi:19815091-0f2c-4d7a-a77f-96cec77038ad`
- auth: query `serviceKey` (env: `ODCLOUD_API_KEY`)
- root: `data`
- params: `page`, `perPage`
- 주의: 서울 열린데이터광장 OpenAPI 아님. odcloud는 별도 provider이다.

#### SEOUL_SHELTER_FLOOD

- source type: FILE (xlsx)
- API 없음
- 월 1회 업데이트
- HTTP 호출 없음, scheduler polling 없음

#### KMA_WEATHER

- endpoint: `https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst`
- auth: query `ServiceKey` (env: `KMA_API_KEY`)
- 필수: `ServiceKey`, `base_date`, `base_time`, `nx`, `ny`
- root: `response.body.items.item`
- **주의: `base_time` 고정 금지. polling 시각 기준 직전 정시를 동적으로 계산해야 한다.**
  - 정책: minute < 45 → 1시간 전 HH:00, minute >= 45 → 현재 HH:00

#### AIR_KOREA_AIR_QUALITY

- endpoint: `https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty`
- auth: query `serviceKey` (env: `AIR_KOREA_API_KEY`)
- root: `response.body.items`
- quota: 500/day
- 주의: 초기 계약 문서의 `getMinuDustFrcstDspth`는 오류였음. 현재 endpoint로 smoke OK — 실계정 동작 확인.

### 8.5 Key Masking 규칙

마스킹 대상:
- `serviceKey`
- `ServiceKey`
- `KEY`
- `Authorization`
- `token`

허용:
- `{KEY}`
- `${ENV_VAR}`

금지:
- 실제 API key 저장 (문서, 로그, 테스트, raw payload 모두)
- DUMMY_KEY를 사용한 외부 API 실제 호출

### 8.6 구현 판정 기준

| 항목          | 기준                       |
|-------------|--------------------------|
| endpoint    | 공식 URL과 일치               |
| auth 위치     | query vs path 정확히 구분     |
| params      | 이름/대소문자 일치               |
| response root | 공식 구조 일치               |
| scheduler   | quota 초과 금지              |
| key 처리      | 마스킹 필수                   |
| file source | HTTP 사용 금지               |

### 8.7 배포 전 체크리스트

- [ ] endpoint가 공식 문서와 동일
- [ ] 서울 API는 path KEY 사용
- [ ] 공공데이터 API는 query serviceKey/ServiceKey 사용 (대소문자 공식 계약 기준)
- [ ] 실제 key 저장/로그 없음
- [ ] DUMMY_KEY 호출 없음
- [ ] response root 정상 파싱
- [ ] scheduler quota 준수
- [ ] file source는 polling 제외
- [ ] weather base_time 동적 계산
- [ ] retry 설정이 코드에 반영됨
- [ ] FORESTRY_LANDSLIDE: 승인 완료 후 공식 원문 endpoint/auth 기준으로 검증

## 9. 관련 문서

- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- Redis key contract: `docs/redis-key/redis-key.md`
