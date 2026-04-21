# api-public-read Service Guide

## 1. 책임

이 서비스는 SafeSpot의 공개 조회 read path를 담당한다.

포함:
- shelters nearby 조회
- shelter detail 조회
- disaster alert 조회
- disaster latest 조회
- weather / air quality 조회
- Redis 우선 조회
- Redis miss / 장애 시 RDS fallback
- suppress window 기반 재생성 요청 이벤트 발행

제외:
- 인증 발급
- 관리자 write
- 외부 API 수집
- Redis 직접 대량 갱신 consumer
- 관리자 감사 로그

---

## 2. 절대 규칙

- write API를 만들지 않는다.
- 관리자 수정 API를 만들지 않는다.
- 외부 API를 직접 호출하지 않는다.
- Redis miss는 RDS fallback으로 처리한다.
- fallback 자체는 즉시 응답하고, 재생성은 비동기 요청으로 처리한다.
- suppress window 정책을 준수한다.

---

## 3. 구현 방향

### 조회 우선순위
1. Redis 조회
2. miss 또는 장애 시 RDS fallback
3. 응답 반환
4. 필요 시 재생성 요청 이벤트 발행

### 캐시
- key naming은 `packages/common-redis-keys`를 사용한다
- 키 형식을 임의로 만들지 않는다
- TTL 정책은 문서 기준을 따른다

### fallback
- fallback은 예외 상황이 아니라 정상 경로로 간주한다
- Redis 장애 시 전체 요청 실패보다 degraded read를 우선한다

### suppress
- 동일 키에 대해 일정 시간 내 중복 재생성 이벤트 발행을 제한한다
- suppress window는 문서 기준값을 따른다

---

## 4. 수정 가능 범위

주 수정 대상:
- `src/main/java/.../shelter`
- `src/main/java/.../disaster`
- `src/main/java/.../environment`
- `src/main/java/.../cache`
- `src/main/resources`

수정 금지:
- `services/api-core/**`
- `services/external-ingestion/**`
- `services/async-worker/**`

---

## 5. 코드 작성 원칙

- controller는 query parameter validation과 응답 변환만 담당
- fallback 정책은 service 계층에 둔다
- Redis 접근 실패는 graceful degrade 처리
- read model 응답에서 개인정보 금지
- 위치값은 저장하지 않는다
- 로그에는 요청 좌표 원문을 남기지 않는다

---

## 6. 우선 확인 문서

- 최신 REST API 명세
- Redis 캐시 문서
- 개인정보 정책 문서
- RDS 설계 문서
- external-ingestion 데이터 적재 구조 문서

---

## 7. 금지 패턴

금지:
- 관리자 write endpoint 추가
- event consumer 추가
- polling loop 추가
- 외부 공공 API 직접 호출
- domain write 로직 포함
- packages에 read service 구현 공유