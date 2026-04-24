# async-worker

SQS consumer 기반 Redis cache refresh / Read Model worker.

---

## 구조

두 개의 독립 Lambda 함수로 배포된다.

| Lambda | Spring Profile | 소비 큐 | 담당 |
|--------|---------------|---------|------|
| `cache-worker` | `cache-worker` | evacuation-events, environment-events | shelter:status, environment read models SET |
| `readmodel-worker` | `readmodel-worker` | disaster-events | `disaster:detail:{alertId}`, `disaster:messages:recent:seoul`, `disaster:message:core:seoul`, `disaster:messages:list:seoul` SET |

---

## 환경 변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/safespot` |
| `DB_USERNAME` | DB 사용자 | `safespot` |
| `DB_PASSWORD` | DB 비밀번호 | — |
| `REDIS_HOST` | Redis 호스트 | `localhost` |
| `REDIS_PORT` | Redis 포트 (기본값 6379) | `6379` |

---

## 로컬 테스트

### 전체 테스트 실행

```bash
./gradlew :services:async-worker:test
```

### 특정 클래스만 실행

```bash
./gradlew :services:async-worker:test --tests "com.safespot.asyncworker.consumer.*"
./gradlew :services:async-worker:test --tests "com.safespot.asyncworker.service.environment.*"
./gradlew :services:async-worker:test --tests "com.safespot.asyncworker.service.disaster.*"
./gradlew :services:async-worker:test --tests "com.safespot.asyncworker.redis.RedisCacheWriterTest"
```

### 컨텍스트 로드 테스트 (Spring 빈 충돌 검증)

```bash
./gradlew :services:async-worker:test --tests "com.safespot.asyncworker.context.*"
```

### 테스트 리포트 확인

```bash
open services/async-worker/build/reports/tests/test/index.html
```

---

## cache-worker 로컬 실행

Lambda 핸들러를 직접 실행할 수는 없지만, Spring Boot 컨텍스트는 아래 방식으로 올릴 수 있다.

```bash
DB_URL=jdbc:postgresql://localhost:5432/safespot \
DB_USERNAME=safespot \
DB_PASSWORD=password \
REDIS_HOST=localhost \
REDIS_PORT=6379 \
./gradlew :services:async-worker:bootRun \
  --args='--spring.profiles.active=cache-worker'
```

## readmodel-worker 로컬 실행

```bash
DB_URL=jdbc:postgresql://localhost:5432/safespot \
DB_USERNAME=safespot \
DB_PASSWORD=password \
REDIS_HOST=localhost \
REDIS_PORT=6379 \
./gradlew :services:async-worker:bootRun \
  --args='--spring.profiles.active=readmodel-worker'
```

---

## 빌드

```bash
./gradlew :services:async-worker:build
```

Fat JAR 위치: `services/async-worker/build/libs/async-worker-*.jar`

---

## 핵심 설계 원칙

- **RDS = source of truth** — Redis는 파생 데이터. Redis 장애 시 RDS fallback으로 복구
- **SQS at-least-once** — 중복 수신을 전제로 설계. idempotency key로 no-op 처리
- **실패 메시지 보존** — Redis 실패 / 파싱 실패 / 검증 실패 시 `BatchItemFailure` 반환 → SQS 재시도 → DLQ
- **성공 ACK 조건** — 정상 처리 완료 후에만 성공 반환. 절대 silent ignore 금지

---

## 현재 상태 (2026-04-22 기준)

### 해결된 이슈

| 이슈 | 해결 방법 |
|------|----------|
| Redis 실패 삼킴 | `RedisCacheWriter` 예외 전파 → `RedisCacheException` / `EventProcessingException` → `BatchItemFailure` |
| idempotency null 응답 무시 | null SETNX 응답 → `RedisCacheException` 즉시 throw |
| unsupported collectionType silent ignore | `EnvironmentCacheService.rebuild()` default branch → `EventProcessingException` |
| worker 빈 그래프 혼재 | `@Profile` 분리 + `SpringApplicationBuilder.profiles()` → 프로필별 독립 컨텍스트 |
| payload 무검증 처리 | `EnvironmentCacheService` / `DisasterReadModelService` `validate()` 추가 |
| Java 버전 미고정 | 루트 `build.gradle` Java 21 toolchain 적용 |
| packages 모듈 오선언 | `settings.gradle`에서 packages 제거, 문서 정정 |
| 문서 TTL 불일치 | env TTL 60분 → 120분 전체 문서 통일 |

### 테스트 커버리지

| 대상 | 클래스 수 | 테스트 수 |
|------|----------|---------|
| SQS 처리 (배치 실패 / 재시도) | 1 | 8 |
| 프로필 격리 (빈 그래프 분리) | 2 | 6 |
| Envelope 파싱 | 1 | 6 |
| Handler (payload / 예외 전파) | 6 | 17 |
| Redis (SET/DEL 실패) | 1 | 4 |
| Idempotency | 1 | 4 |
| Service (환경/재난/대피소) | 3 | 15 |
| CongestionLevel | 1 | 9 |
| **합계** | **15** | **69** |

### 검증 한계 및 남은 작업

- 컨텍스트 테스트는 `DataSource` / `StringRedisTemplate`을 mock으로 대체한다.
  실제 JDBC 연결 안정성과 Redis 커넥션 풀 동작은 인프라가 있는 환경에서 별도 확인 필요.
- Lambda 핸들러 cold-start / SnapStart 동작은 AWS 환경에서만 검증 가능.
- `gradlew test` 최종 확인은 Java 21 설치된 환경에서 직접 실행 필요.

---

## Redis TTL 정책

| 키 패턴 | TTL | 비고 |
|---------|-----|------|
| `shelter:status:{shelterId}` | 30초 | 재난 상황 즉각 반영 |
| `disaster:messages:recent:seoul` | 5분 | recent read model |
| `disaster:message:core:seoul` | 5분 | core read model |
| `disaster:messages:list:seoul` | 5분 | Top 50 list read model |
| `disaster:detail:{alertId}` | 60분 | detail read model |
| `environment:weather:{nx}:{ny}` | 120분 | fallback 안정성 기준 (데이터 신선도 아님) |
| `environment:air:{stationName}` | 120분 | fallback 안정성 기준 (데이터 신선도 아님) |

## 재난 read model 재생성 규칙

- 재생성 순서는 `detail -> recent -> core -> list`를 따른다.
- worker는 정규화된 DB 데이터만 사용한다.
- raw 메시지를 worker에서 다시 분류하지 않는다.
- `isInScope=false` 메시지는 public Redis read model에 포함하지 않는다.
- retired key인 `disaster:active`, `disaster:latest:*`, `disaster:alert:list`는 재생성하지 않는다.

> env TTL은 외부 API 수집 주기와 무관하다.
> 수집 완료 이벤트(EnvironmentDataCollected) 수신 즉시 overwrite로 갱신된다.
> 120분 TTL은 Redis 장애 또는 수집 지연 시 기존 캐시 데이터를 유지하기 위한 안전망이다.
