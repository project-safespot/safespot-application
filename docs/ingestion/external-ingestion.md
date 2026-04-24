# External Ingestion

This document defines the `external-ingestion` contract for external collection, normalization before DB write, and post-write event publication.

## 1. Scope

Current MVP scope is Seoul only.

- external collection and normalization operate only on Seoul data for the MVP
- region-derived outputs must stay within Seoul

## 2. Current Implementation vs Target State

Current implementation:

- external sources are collected on polling loops or CronJobs
- normalized data is written to RDS before any downstream read model rebuild
- raw and canonical values are stored together for disaster alerts
- post-commit events may trigger later async processing, but `external-ingestion` does not build Redis read models directly

Target architecture:

- the same scope remains, but durability and replay requirements must hold even if the publication mechanism changes

## 3. Disaster Message Normalization Contract

`SAFETY_DATA_ALERT` and equivalent disaster-message sources must be normalized before DB storage.

Required flow:

- external disaster message
- `external-ingestion` normalizer
- DB stores raw + canonical values
- async-worker builds Redis read models later from normalized DB data
- `api-public-read` reads Redis only and does not re-normalize disaster messages

### 3.1 Supported Canonical `disasterType`

SafeSpot MVP public-read scope supports only these canonical values:

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

### 3.2 Out-Of-Scope Type Policy

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

### 3.5 Raw + Canonical Storage Policy

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

## 4. Weather Contract

Weather is region-scoped for the MVP.

- region input is mapped to grid coordinates
- Seoul region -> Seoul grid mapping
- `nx` / `ny` remain the storage and cache selectors

## 5. Event Publication Contract

After normalized data is committed to RDS:

- publish after DB commit
- publish must be durable
- do not allow log-only failure handling
- preserve the full envelope for replay or failure-channel recovery

## 6. Observability

Structured log and metric labels may include `queue_name`, but:

- `queue_name` must be a logical queue name
- never log or label a raw queue URL as `queue_name`

## 7. Responsibility Split

`external-ingestion` owns:

- external API collection
- raw payload persistence
- keyword and token extraction for disaster messages
- canonical `disasterType` mapping
- canonical `messageCategory` mapping
- canonical `level` / `levelRank` mapping
- `isInScope` decision
- normalized RDS writes
- post-commit event publication

`external-ingestion` does not own:

- Redis read model rebuild
- direct Redis `SET`
- direct Redis `DEL`
- public read APIs
- public-read reclassification or re-normalization
- worker retry / DLQ execution

Later-stage ownership:

- async-worker: build Redis read models from normalized DB data
- api-public-read: read Redis read models only

## 8. Related Documents

- event envelope: `docs/event/event-envelope.md`
- async worker behavior: `docs/event/async-worker.md`
- Redis key contract: `docs/redis-key/redis-key.md`
