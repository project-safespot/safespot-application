# async-worker Service Guide

## 1. 책임

이 서비스는 비동기 이벤트 소비와 Redis 갱신을 담당한다.

포함:
- SQS consumer
- event parsing
- idempotent 처리
- Redis key 갱신
- retry / DLQ 대응
- 소비자 관점 observability

제외:
- 공개 조회 API
- 관리자 write API
- 외부 API polling
- raw payload 저장
- external-ingestion 정규화
- 인증 발급

---

## 2. 절대 규칙

- API controller를 만들지 않는다.
- 외부 공공 API를 직접 호출하지 않는다.
- source 데이터를 RDS에서 새로 수집하지 않는다.
- consumer는 event-driven으로만 동작한다.
- idempotency를 무시하지 않는다.
- payload 계약을 임의로 변경하지 않는다.

---

## 3. 구현 방향

### 이벤트 소비
- shared event schema 사용
- eventType별 handler 분리
- invalid payload는 명확히 분리 처리

### Redis 갱신
- key naming은 shared contract 사용
- TTL 정책은 문서 기준 준수
- overwrite / merge 정책은 이벤트 종류별로 명확히 분리
- Redis 실패 시 재시도 및 DLQ 정책 반영

### idempotency
- 중복 소비 가능성을 전제로 구현
- 같은 idempotencyKey 재처리 시 side effect 최소화

---

## 4. 수정 가능 범위

주 수정 대상:
- `src/main/java/.../consumer`
- `src/main/java/.../handler`
- `src/main/java/.../redis`
- `src/main/java/.../idempotency`
- `src/main/resources`

수정 금지:
- `services/api-core/**`
- `services/api-public-read/**`
- `services/external-ingestion/**`

---

## 5. 코드 작성 원칙

- event parsing과 business handling을 분리
- Redis write와 retry 정책을 분리
- handler는 eventType 중심으로 나눈다
- 공통 key 형식은 packages 사용
- 로그에는 payload 전체를 덤프하지 않는다
- traceId, eventId, idempotencyKey 중심으로 추적한다

---

## 6. 우선 확인 문서

- 이벤트 envelope 명세
- Redis key 명세
- external-ingestion refresh event 명세
- 캐시 TTL 정책 문서
- 운영/모니터링 문서

---

## 7. 금지 패턴

금지:
- REST endpoint 생성
- 관리자 감사 로그 직접 생성
- 외부 API 수집 로직 추가
- RDS를 source of truth처럼 재해석하는 구현
- packages에 worker 구현 공유