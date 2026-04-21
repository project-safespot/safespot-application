# async-worker Service Guide

## 1. 목적

이 저장소는 SafeSpot 재난 대피 서비스 애플리케이션 monorepo이다. 본 문서는 **루트 정책 문서 (Global Policy)** 이며: - 저장소 구조 - 서비스 경계 - 공통 규칙 - Git / Worktree 운영 방식 - 문서 Source of Truth 를 정의한다. > 구현 상세 / 도메인 로직 / 코드 규칙은 > 각 서비스 디렉터리의 CLAUDE.md가 우선한다. ---

- 저장소 내부 구조
- 워커 책임 범위
- Redis 캐시 갱신 규칙
- 이벤트 소비 정책
- 장애 처리 방식

을 정의한다.

> 루트 저장소 정책은 루트 CLAUDE.md가 우선한다.
> 충돌 발생 시 루트 정책을 따른다.

---

## 2. 저장소 구조

```
safespot-application/
├── services/
│   ├── api-core/
│   ├── api-public-read/
│   ├── external-ingestion/
│   └── async-worker/          # ← 이 서비스의 담당 디렉터리
├── packages/
│   ├── event-schema/
│   ├── common-redis-keys/
│   └── observability-contract/
├── docs/
├── deploy/
└── CLAUDE.md                  # ← 이 문서
```

### 구조 원칙

- `services/async-worker/` → 이 worktree에서 수정 가능한 유일한 서비스 디렉터리
- `docs/` → Source of Truth. 코드보다 문서가 우선
- `packages/` → 서비스 간 공유 계약. 단일 서비스 단독 수정 금지
- `services/` 하위 다른 서비스 → **직접 수정 금지**

---

## 3. 워커 책임 범위 (Critical)

### 3.1 cache-worker

소비 이벤트:
- `EvacuationEntryCreated`
- `EvacuationEntryExited`
- `EvacuationEntryUpdated`
- `ShelterUpdated`
- `EnvironmentDataCollected`

담당 Redis 키 (SET only):

| Redis 키 | TTL | 설명 |
|---|---|---|
| `shelter:status:{shelterId}` | 30초 | 대피소 현재인원·잔여인원·혼잡도 |
| `env:weather:{nx}:{ny}` | 120분 | 날씨 데이터 |
| `env:air:{station_name}` | 120분 | 대기질 데이터 |
| `disaster:active:{region}:{disasterType}` | 3분 | 지역별 활성 재난 목록 |
| `disaster:detail:{alert_id}` | 10분 | 개별 재난 알림 상세 |

현재인원 계산: RDS의 `ENTERED` 상태 레코드 `COUNT()` 결과를 Redis에 캐싱

---

### 3.2 readmodel-worker

소비 이벤트:
- `DisasterDataCollected`

담당 Redis 키 (SET / 조건부 DEL 후 재생성):

| Redis 키 | TTL | 설명 |
|---|---|---|
| `disaster:active:{region}` | 2분 | 지역별 활성 재난 목록 |
| `disaster:alert:list:{region}:{disasterType}` | 5분 | 지역+유형별 전체 알림 목록 |
| `disaster:detail:{alertId}` | 10분 | 개별 재난 알림 상세 |

재구성 방식: 이벤트 수신 시 해당 키 DEL 후 RDS 기반 전체 재조회 → SET

---

## 4. 데이터 원칙 (중요)

### 4.1 Cache-Aside 패턴 엄수

- async-worker는 Redis **SET만** 담당
- Redis **DEL은 api-core**가 담당 (write 트랜잭션 완료 시점)
- **Read path는 api-public-read**가 담당

> DEL과 SET을 동일 컴포넌트에 두면 경쟁 조건 발생 → 분리 필수

---

### 4.2 Redis 접근 제한

```
cache-worker    → Redis SET  (허용)
readmodel-worker → Redis SET / 조건부 DEL (허용)
external-ingestion Normalizer → Redis 직접 접근 (절대 금지)
```

external-ingestion은 SQS 이벤트 발행만 수행. Redis 접근 없음.

---

### 4.3 Read Path 개입 금지

```
Read path: Client → api-public-read → Redis → RDS fallback
```

- async-worker는 Read path에 **절대 개입하지 않는다**
- SQS를 Read path에 삽입하는 구조 금지
- Redis DOWN 시 RDS 동기 fallback은 **api-public-read**가 처리

---

## 5. SQS 큐 구조

| 큐 이름 | 발행 주체 | 소비 워커 |
|---|---|---|
| `evacuation-events` | api-core | cache-worker |
| `disaster-events` | external-ingestion Normalizer | readmodel-worker |
| `environment-events` | external-ingestion Normalizer | cache-worker |
| `dlq-evacuation-events` | SQS (재시도 초과) | — (ops 모니터링) |
| `dlq-disaster-events` | SQS (재시도 초과) | — (ops 모니터링) |
| `dlq-environment-events` | SQS (재시도 초과) | — (ops 모니터링) |

DLQ maxReceiveCount: **3회**

---

## 6. 이벤트 소비 정책

### 6.1 멱등성

- SQS at-least-once 특성상 중복 수신 가능
- 캐시 키 overwrite는 안전 → **no-op 없이 매번 SET**
- 이벤트별 중복 소비 기준: `entryId + eventType` 복합키

### 6.2 재시도 정책

- SQS 기반 워커: **DLQ 적용** (maxReceiveCount=3)
- Scheduler 기반 워커: **내부 재시도 3회 고정** + CloudWatch 로깅
  - 3회 연속 실패 시 CloudWatch 알람 트리거

### 6.3 실패 처리 경계

- 이벤트 소비 실패 → 재시도 → DLQ 이동 : **async-worker 책임**
- DLQ 이후 분석 및 복구 : **ops 영역 협력**
- Redis 캐시 재생성 실패 → DLQ 이동까지만 담당
- Read path 캐시 복구 (Cache-Aside fallback) : **api-public-read 책임**

---

## 7. 문서 Source of Truth

다음 문서가 구현 기준이다. **코드가 문서와 충돌 시 코드를 수정한다.**

| 문서 경로 | 내용 |
|---|---|
| `docs/event/event-envelope.md` | 공통 이벤트 Envelope 스키마 |
| `docs/event/ingestion-refresh-event.md` | 수집 완료 이벤트 계약 |
| `docs/redis-key/redis-key.md` | Redis 키 명세 |
| `docs/redis-key/cache-ttl.md` | TTL 정책 |
| `docs/monitoring/monitoring.md` | CloudWatch 알람 및 메트릭 기준 |

---

## 8. Terraform 모듈 규칙

### 8.1 모듈 경계

- `modules/messaging/sqs/` : SQS 큐 + DLQ + event source mapping 정의
- `modules/application/lambda-worker/` : Lambda 함수 + IAM role + CloudWatch 로그 그룹 정의
- `versions.tf` : **루트에만 위치** (모듈 내 중복 선언 금지)

### 8.2 태깅 규칙

```hcl
tags = {
  domain = "application"
  service = "async-worker"
}
```

### 8.3 cross-module 참조

- VPC ID / private_subnet_ids / lambda_sg_id → `modules/network/vpc` output 수령
- rds_endpoint / redis_endpoint / db_secret_arn → `modules/data` output 수령
- 직접 생성 금지

---

## 9. 포트 매핑

| 출발지 | 목적지 | 포트 | 프로토콜 |
|---|---|---|---|
| cache-worker Lambda | RDS | 5432 | TCP |
| cache-worker Lambda | Redis | 6379 | TCP |
| readmodel-worker Lambda | Redis | 6379 | TCP |
| Lambda (공통) | Secrets Manager | 443 | TCP |
| Lambda (공통) | SQS | 443 | TCP |
| Lambda (공통) | CloudWatch Logs | 443 | TCP |

---

## 10. 금지사항 (강제)

### 10.1 Redis 접근 제한 위반

- async-worker가 Redis DEL 수행 금지 (readmodel-worker의 조건부 DEL 제외)
- external-ingestion Normalizer의 Redis 직접 접근 허용 금지

### 10.2 Read Path 개입

- SQS를 Read path에 삽입하는 구조 금지
- async-worker에서 조회 응답 직접 처리 금지

### 10.3 원본 데이터 저장

- Redis에 원본 데이터 (RDS Source of Truth) 저장 금지
- RDS bypass 금지
- 캐시 갱신 없이 RDS 반복 조회 금지

### 10.4 서비스 경계 위반

- api-core / api-public-read / external-ingestion 디렉터리 직접 수정 금지
- 공통 이벤트 스키마 변경은 `packages/event-schema` 통해 반영

---

## 11. 변경 프로세스

이벤트 스키마 또는 Redis 키 계약 변경 시:

1. `packages/event-schema` 또는 `packages/common-redis-keys` 수정
2. 팀 합의 (재영 리뷰)
3. 영역 확정 계약서 및 모듈 인터페이스 정의서 반영
4. 코드 반영

Lambda 코드 변경 시:
```
worktree/async-worker branch
→ 구현
→ PR 생성
→ review worktree 검토
→ main merge
```

---

## 12. 핵심 설계 철학

- **Lambda Stateless** : 상태는 Redis(캐시)와 RDS(원본)에만 위치
- **Cache-Aside 역할 분리** : SET(async-worker) / DEL(api-core) / Read(api-public-read)
- **SQS at-least-once 대응** : 캐시 overwrite 기반 멱등성
- **DLQ 격리** : 재시도 한도 초과 메시지를 격리하여 정상 처리 흐름 보호
- **문서 기반 개발** : spec과 다른 구현 금지, 암묵적 변경 금지