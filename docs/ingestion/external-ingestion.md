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

## 8. 관련 문서

- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- Redis key contract: `docs/redis-key/redis-key.md`
