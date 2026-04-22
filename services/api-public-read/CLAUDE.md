# api-public-read Service Guide

## 1. 책임

`api-public-read`는 SafeSpot의 공개 조회 read path를 담당한다.

포함:

- shelters nearby 조회
- shelter detail 조회
- disaster alert 조회
- disaster latest 조회
- weather / air quality 조회
- Redis 우선 조회
- Redis miss/down/parse error 시 RDS fallback
- suppress window 기반 cache regeneration 요청 이벤트 발행
- api-public-read cache/fallback metric/log

제외:

- 인증 발급
- 관리자 write
- 관리자 감사 로그
- 외부 API 수집
- Redis 직접 대량 갱신 consumer
- Redis SET / read model 재생성 실행
- worker retry / DLQ 처리

---

## 2. 절대 규칙

- write API를 만들지 않는다.
- 관리자 수정 API를 만들지 않는다.
- 외부 API를 직접 호출하지 않는다.
- Redis miss/down/parse error는 RDS fallback으로 처리한다.
- fallback 자체는 즉시 응답하고, 재생성은 비동기 요청 이벤트로 처리한다.
- read path에 SQS 또는 async worker를 삽입하지 않는다.
- suppress window 정책을 준수한다.
- Redis key를 metric label로 사용하지 않는다.

---

## 3. 우선 확인 문서

동작을 변경하기 전 관련 문서를 먼저 확인한다.

- API 공통 정책: `docs/api/api-common.md`
- api-public-read API: `docs/api/api-public-read.md`
- 이벤트 envelope / payload: `docs/event/event-envelope.md`
- worker 처리 기준: `docs/async/async-worker.md`
- monitoring metric/log: `docs/monitoring/monitoring.md`
- Redis key / TTL: `docs/redis-key/redis-key.md`, `docs/redis-key/cache-ttl.md`
- RDS schema: `docs/data/db-schema.md`

---

## 4. 구현 방향

### 조회 우선순위

```text
1. Redis 조회
2. miss/down/parse error 시 RDS fallback
3. 응답 반환
4. suppress window 통과 시 cache regeneration 요청 이벤트 발행
```

### Cache-aside

- Redis hit이면 cached value를 반환한다.
- Redis miss/down/parse error이면 RDS fallback을 수행한다.
- fallback은 예외 상황이 아니라 정상적인 degraded read 경로로 취급한다.
- Redis 장애 시 전체 요청 실패보다 degraded read를 우선한다.
- read path에서 외부 API를 직접 호출하지 않는다.

### Cache regeneration request

- fallback 이후 필요한 경우 cache regeneration 요청 이벤트를 발행한다.
- 동일 cache key에 대해 API 인스턴스 로컬 suppress window를 적용한다.
- suppress window 기준은 `docs/api/api-public-read.md`와 `docs/event/event-envelope.md`를 따른다.
- cache regeneration 실행은 async-worker 책임이다.

### Redis key

- key naming은 `docs/redis-key/` 문서를 기준으로 한다.
- key 형식을 임의로 만들지 않는다.
- key는 가능하면 상수 또는 helper를 통해 관리한다.
- Redis key 원문은 metric label에 넣지 않고 structured log field로만 남긴다.

### Monitoring

- endpoint별 cache request result를 계측한다.
- endpoint별 fallback reason을 계측한다.
- DB fallback query count와 latency를 계측한다.
- Redis write 결과를 계측할 경우 endpoint와 result 중심으로만 남긴다.
- metric 이름과 label은 `docs/monitoring/monitoring.md`를 따른다.

---

## 5. 수정 가능 범위

주 수정 대상:

- `src/main/java/.../controller`
- `src/main/java/.../service`
- `src/main/java/.../cache`
- `src/main/java/.../event`
- `src/main/java/.../metrics`
- `src/main/resources`

관련 변경 시 함께 확인:

- `docs/api/api-public-read.md`
- `docs/event/event-envelope.md`
- `docs/async/async-worker.md`
- `docs/monitoring/monitoring.md`
- `docs/redis-key/redis-key.md`
- `docs/redis-key/cache-ttl.md`

수정 금지:

- `services/api-core/**`
- `services/external-ingestion/**`
- `services/async-worker/**`

---

## 6. 코드 작성 원칙

- controller는 query parameter validation과 응답 변환만 담당한다.
- fallback 정책은 service/cache 계층에 둔다.
- Redis 접근 실패는 graceful degrade 처리한다.
- read model 응답에서 개인정보를 노출하지 않는다.
- 위치값은 저장하지 않는다.
- 로그에는 요청 좌표 원문을 불필요하게 남기지 않는다.
- event payload와 cache key는 문서 기준을 따른다.

---

## 7. 금지 패턴

- 관리자 write endpoint 추가
- event consumer 추가
- polling loop 추가
- 외부 공공 API 직접 호출
- domain write 로직 포함
- Redis SET 기반 cache rebuild 직접 실행
- packages에 read service 구현 공유
