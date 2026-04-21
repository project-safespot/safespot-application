# external-ingestion Service Guide

## 1. 책임

이 서비스는 외부 API 수집과 정규화를 담당한다.

포함:
- polling loop
- CronJob 실행 로직
- 외부 API 호출
- raw payload 저장
- 정규화
- RDS 적재
- 캐시 갱신 이벤트 발행
- 실행 이력 / 오류 이력 기록

제외:
- 공개 조회 API
- 관리자 write API
- Redis 직접 갱신
- 공개 응답 조립
- API 인증 발급

---

## 2. 절대 규칙

- Redis를 직접 갱신하지 않는다.
- RDS 적재 완료 전 캐시 갱신 이벤트를 발행하지 않는다.
- raw payload 저장과 normalized write를 혼동하지 않는다.
- 외부 API별 polling 주기를 임의 변경하지 않는다.
- source / schedule / execution log / raw payload / normalization error 책임을 섞지 않는다.

---

## 3. 구현 방향

### 수집
- source별 polling 방식 또는 CronJob 방식을 문서 기준으로 구현
- 호출 제한이 있는 API는 rate limit을 준수
- 네트워크 오류는 제한된 재시도 후 실패 이력 기록

### 저장
- 원본 응답은 raw payload 테이블에 저장
- 정규화 결과는 대상 테이블에 저장
- 원본과 정규화 결과를 한 테이블에 섞지 않는다

### 정규화
- source별 필드 변환 책임을 분리
- 검증 실패는 normalization error에 기록
- partial success 가능성을 반영

### 이벤트
- Redis refresh는 직접 수행하지 않고 이벤트만 발행
- event envelope는 shared contract를 따른다

---

## 4. 수정 가능 범위

주 수정 대상:
- `src/main/java/.../collector`
- `src/main/java/.../scheduler`
- `src/main/java/.../normalizer`
- `src/main/java/.../outbox-or-publisher`
- `src/main/java/.../externalapi`
- `src/main/resources`

수정 금지:
- `services/api-core/**`
- `services/api-public-read/**`
- `services/async-worker/**`

---

## 5. 코드 작성 원칙

- source별 client와 normalizer를 분리한다
- 테이블 책임을 서비스 클래스 하나에 몰아넣지 않는다
- raw payload 저장 후 normalize queue publish 순서를 지킨다
- normalize 후 RDS 적재 완료 뒤 refresh event를 발행한다
- shelter selective upsert 정책을 지킨다
- 장애 우선순위를 source별로 구분한다

---

## 6. 우선 확인 문서

- external-ingestion 상세 설계 문서
- external-ingestion 전용 RDS 설계 문서
- 최신 RDS 설계 문서
- 캐시 refresh 이벤트 계약
- Redis key / TTL 문서

---

## 7. 금지 패턴

금지:
- Redis 직접 SET
- 공개 조회용 endpoint 구현
- 관리자 수정 endpoint 구현
- packages에 ingestion 구현체 공유
- 다른 서비스 repository 직접 참조