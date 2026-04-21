# api-core Service Guide

## 1. 책임

이 서비스는 SafeSpot의 관리자 보호 워크로드와 인증/쓰기 API를 담당한다.

포함:
- 로그인
- 내 정보 조회
- 관리자 dashboard 조회
- 관리자 입소 등록 / 퇴소 / 수정
- shelter 운영정보 수정
- 동기 트랜잭션 처리
- 감사 로그 기록
- DB commit 이후 이벤트 발행
- Redis 캐시 무효화(DEL)

제외:
- 공개 조회 API
- 외부 API 수집
- Redis 직접 상태 재계산
- async consumer
- 캐시 refresh worker

---

## 2. 절대 규칙

- write 성공 전에 이벤트를 발행하지 않는다.
- DB commit 이후 이벤트를 발행한다.
- 개인정보를 로그에 기록하지 않는다.
- 공개 조회용 read model을 여기서 직접 재구성하지 않는다.
- external-ingestion 로직을 넣지 않는다.
- worker용 polling / consumer 로직을 넣지 않는다.

---

## 3. 구현 방향

### 인증
- JWT Access Token only
- Refresh Token 미도입
- role 기반 인가

### 관리자 write
- 트랜잭션 경계 명확화
- 감사 로그와 상태 변경 기록을 남긴다
- reason 필드 정책은 문서 기준을 따른다

### 이벤트
- 이벤트 envelope는 `packages/event-schema`를 사용한다
- changedFields는 필드명만 넣는다
- 개인정보 값 자체를 payload에 넣지 않는다

### Redis
- 직접 SET보다 무효화(DEL) 우선
- read model 재생성은 다른 워크로드 책임으로 남긴다

---

## 4. 수정 가능 범위

주 수정 대상:
- `src/main/java/.../auth`
- `src/main/java/.../admin`
- `src/main/java/.../audit`
- `src/main/java/.../event`
- `src/main/resources`

수정 최소화 대상:
- shared package 계약
- deploy manifest

수정 금지:
- `services/api-public-read/**`
- `services/external-ingestion/**`
- `services/async-worker/**`

---

## 5. 코드 작성 원칙

- controller는 요청/응답 매핑만 담당
- 비즈니스 규칙은 service에 둔다
- repository는 persistence만 담당
- event publish는 transaction 이후로 분리
- exception은 API 에러 코드와 일치시킨다
- 개인정보 응답 노출 금지 규칙을 준수한다

---

## 6. 우선 확인 문서

- 최신 REST API 명세
- 권한 정책 문서
- 개인정보 정책 문서
- RDS 설계 문서
- Redis invalidation 관련 문서

---

## 7. 금지 패턴

금지:
- “공통화”를 이유로 domain 로직을 packages로 이동
- 다른 서비스 코드 직접 import
- read 전용 fallback 로직 구현
- ingestion 스케줄링 추가
- Redis consumer 추가