# SafeSpot scenario-simulator 개발 작업

## 목표

SafeSpot 개발/테스트 환경에서 실제 재난문자나 수동 입소 등록 없이도 다음 상황을 재현할 수 있는 전용 테스트 도우미 서비스를 개발한다.

- 재난문자 발생 시뮬레이션
- 랜덤/가중치 기반 대피소 입소자 대량 등록
- 복합 시나리오 실행
- 테스트 데이터 cleanup
- 이벤트 발행 흐름 검증
- Redis/readmodel/cache regeneration 흐름 검증
- proactive-scale-controller 검증용 ProactiveScaleRequested 이벤트 생성

기존 논의상 `proactive-scale-controller`는 재난문자 수집 → 재난 레벨 판정 → api-public-read replicas 선증가 → Pending Pod 유도 → Karpenter Node 선증설 흐름을 검증해야 한다. scenario-simulator는 이 흐름을 실제 재난 없이 재현하기 위한 dev/test 전용 도구다.

## 신규 서비스

가능하면 신규 서비스로 추가한다.

services/scenario-simulator

Spring Boot 기반으로 작성한다.

## 절대 금지

다음 작업은 하지 않는다.

- git push
- main 브랜치 변경
- terraform 명령 실행
- kubectl 명령 실행
- aws CLI 실행
- 운영 DB/Redis 접근 코드 추가
- 실제 secret 추가
- 운영 profile에서 simulator 활성화
- production values에서 enabled=true 설정
- 기존 운영 API에 test=true 같은 파라미터 추가
- api-core/api-public-read에 테스트 전용 endpoint 직접 추가
- ReplicaSet 직접 수정
- Node/EC2/Karpenter 직접 제어

## 활성화 조건

scenario-simulator는 local/dev/test profile에서만 활성화한다.

- prod profile에서는 Bean 자체가 뜨지 않아야 한다.
- Kubernetes/Helm values에서는 기본 enabled=false로 둔다.
- dev/local values에서만 enabled=true가 가능하도록 작성한다.
- Ingress 외부 노출은 하지 않는다.
- port-forward 또는 ClusterIP 내부 접근만 전제로 한다.

## 구현할 MVP API

### 1. 재난문자 생성

POST /internal/test/disaster-alerts

Request 예시:

{
  "disasterType": "EARTHQUAKE",
  "region": "SEOUL",
  "level": "HIGH",
  "count": 3,
  "intervalSeconds": 0,
  "publishEvents": true,
  "triggerProactiveScale": true
}

동작:

- 테스트용 disaster_alert 생성
- 필요 시 DisasterAlertCreated 또는 기존 이벤트 계약에 맞는 이벤트 발행
- 필요 시 CacheRegenerationRequested 발행
- triggerProactiveScale=true이면 ProactiveScaleRequested 발행

### 2. 입소자 대량 생성

POST /internal/test/residents/bulk

Request 예시:

{
  "disasterType": "EARTHQUAKE",
  "region": "SEOUL",
  "residentCount": 1000,
  "shelterSelection": "RANDOM",
  "distribution": "WEIGHTED_BY_CAPACITY",
  "entryStatus": "ENTERED",
  "publishEvents": true
}

지원 distribution:

- RANDOM
- WEIGHTED_BY_CAPACITY
- HOTSPOT

동작:

- 대상 대피소 조회
- 랜덤 또는 capacity 가중치 기반으로 대피소 배정
- resident 또는 shelter entry 관련 기존 도메인 모델에 맞춰 입소 데이터 생성
- 수용률/현재 입소자 수 갱신이 필요한 구조라면 기존 로직을 재사용
- 필요 시 readmodel/cache refresh 이벤트 발행

### 3. 복합 시나리오 실행

POST /internal/test/scenarios/run

Request 예시:

{
  "scenarioName": "SEOUL_HIGH_EARTHQUAKE_LOAD",
  "disaster": {
    "disasterType": "EARTHQUAKE",
    "region": "SEOUL",
    "level": "HIGH"
  },
  "residents": {
    "count": 2000,
    "durationSeconds": 120,
    "distribution": "HOTSPOT"
  },
  "cache": {
    "triggerRegeneration": true
  },
  "scale": {
    "triggerProactiveScale": true
  }
}

동작:

- scenarioId 생성
- 재난문자 생성
- 입소자 bulk 생성
- 이벤트 발행
- 실행 결과 summary 반환

### 4. cleanup

POST /internal/test/cleanup

Request 예시:

{
  "scenarioId": "..."
}

동작:

- 해당 scenarioId로 생성된 테스트 데이터를 삭제
- Redis test/cache key 삭제가 안전하게 가능한 경우만 삭제
- 운영 데이터와 구분 불가능한 데이터는 삭제하지 말고 report에 남긴다

## 테스트 데이터 추적

운영 도메인 테이블에 is_test_data 컬럼을 무리하게 추가하지 말고, 우선 test_scenario_record 메타 테이블 방식으로 추적한다.

예상 테이블:

test_scenario_record
- scenario_id
- target_table
- target_id
- created_at

단, 기존 migration 구조와 충돌하지 않게 Flyway migration 파일명을 확인한 뒤 새 migration을 추가한다.

## 이벤트 계약

기존 packages/event-schema 또는 공통 이벤트 모듈을 먼저 조사한다.

새 이벤트를 만들기 전 기존 이벤트 계약을 재사용한다.

필요한 이벤트가 없다면 최소 범위로 추가한다.

ProactiveScaleRequested 이벤트 payload 예시:

{
  "eventType": "ProactiveScaleRequested",
  "payload": {
    "disasterType": "EARTHQUAKE",
    "region": "SEOUL",
    "level": "HIGH",
    "requestedReplicas": 10
  }
}

## Observability

Micrometer metric 추가:

- scenario_simulator_requests_total
- scenario_simulator_disaster_alerts_created_total
- scenario_simulator_residents_created_total
- scenario_simulator_cleanup_total
- scenario_simulator_events_published_total
- scenario_simulator_failures_total

로그에는 다음 필드를 포함한다.

- scenarioId
- scenarioName
- disasterType
- region
- level
- residentCount
- distribution
- publishEvents
- triggerProactiveScale

## 테스트

가능한 범위에서 아래를 수행한다.

- unit test
- service test
- request validation test
- distribution algorithm test
- cleanup tracking test

실행 가능한 빌드/테스트 명령을 찾아서 실행한다.

예상 명령:

./gradlew :services:scenario-simulator:test
./gradlew :services:scenario-simulator:build

프로젝트 구조상 multi-module 설정이 필요하면 settings.gradle, build.gradle 구조를 확인하고 기존 서비스 패턴을 따른다.

## 완료 기준

작업 완료 시 다음을 남긴다.

1. 변경 파일 목록
2. 신규 API 목록
3. 실행한 테스트 명령과 결과
4. 미완료/주의사항
5. 다음 사람이 검토해야 할 부분
6. 추천 커밋 메시지

가능하면 마지막에 로컬 커밋까지 생성한다.

커밋 메시지:

feat: add scenario simulator for disaster and resident load testing
