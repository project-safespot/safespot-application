# safespot-application

## Purpose

본 저장소는 SafeSpot 팀 프로젝트의 애플리케이션 구현 근거를 보존하기 위한 저장소입니다. 프로젝트 당시 AWS EKS, RDS, Redis, ALB, CloudFront 등을 사용해 배포와 부하테스트를 수행했으며, 프로젝트 종료 후 비용 방지를 위해 운영 리소스는 정리했습니다.

이 저장소에는 공개 조회 API, 관리자/쓰기 API, 외부 수집기, SQS/Lambda 기반 async worker, 시나리오 시뮬레이터, 사전 스케일링 컨트롤러가 함께 포함되어 있습니다.

## Project status

- 현재 운영 중인 클러스터나 도메인을 전제하지 않습니다.
- 애플리케이션 코드는 재현 근거로 유지되며, 실제 재배포에는 별도의 AWS 리소스와 환경 변수 재구성이 필요합니다.
- 배포 매니페스트와 Terraform 구조는 각각 `safespot-application-k8s-manifest`, `safespot-terraform` 저장소에서 관리합니다.

## Service structure

`settings.gradle` 기준 Gradle 멀티 모듈 구성은 아래와 같습니다.

- `common`: 서비스 공통 코드
- `services/api-core`: 관리자/쓰기 API와 도메인 이벤트 발행
- `services/api-public-read`: 공개 조회 API와 Redis-first read path
- `services/external-ingestion`: 외부 데이터 수집 및 canonical DB 적재
- `services/async-worker`: SQS/Lambda 기반 Redis 캐시 및 read model 재구성
- `services/scenario-simulator`: 부하/재난 시나리오 이벤트 생성
- `services/pre-scaling-controller`: 읽기 트래픽 급증 전 HPA/Ingress 조정을 돕는 컨트롤러

## Key modules

- `services/api-public-read/src/main/java/com/safespot/apipublicread/cache/FallbackSingleFlight.java`
  Redis miss 이후 동일 key의 동시 RDS fallback을 프로세스 내부에서 단일 실행으로 합칩니다.
- `services/api-public-read/src/main/java/com/safespot/apipublicread/cache/DistributedFallbackGuard.java`
  일부 fallback 경로에서 Redis `SETNX` 기반 분산 가드를 사용해 인스턴스 간 중복 DB fallback을 줄입니다.
- `services/api-public-read/src/main/java/com/safespot/apipublicread/cache/PublicReadMetricRecorder.java`
  cache request, fallback, DB fallback latency, cache regeneration 관련 메트릭을 기록합니다.
- `services/async-worker/src/main/java/com/safespot/asyncworker/AsyncWorkerHandler.java`
  Lambda 진입점으로 `async-worker` 프로필 Spring 컨텍스트를 초기화하고 SQS batch를 처리합니다.
- `services/async-worker/src/main/java/com/safespot/asyncworker/consumer/SqsBatchProcessor.java`
  envelope 파싱, idempotency, handler dispatch, partial batch failure 반환을 담당합니다.
- `services/async-worker/src/main/java/com/safespot/asyncworker/handler/CacheRegenerationAsyncWorkerHandler.java`
  shelter/environment/disaster 계열 `CacheRegenerationRequested` 이벤트를 한 곳에서 분기 처리합니다.

## Portfolio evidence

- Troubleshooting
  `api-public-read`는 Redis-first read 경로 위에 local singleflight, suppress window, 분산 fallback guard를 조합해 miss storm를 완화합니다.
- Observability
  `api-public-read`는 `health`, `metrics`, `prometheus` actuator endpoint를 노출하고, `async-worker`는 `WorkerMetrics`와 CloudWatch meter registry 연동 코드를 포함합니다.
- EKS
  이 저장소의 `pre-scaling-controller`와 `deploy/`, `services/pre-scaling-controller/k8s/`는 애플리케이션 쪽에서 필요했던 스케일링 제어 근거를 보여줍니다. 실제 클러스터 매니페스트는 별도 manifest 저장소에 있습니다.

## Notes for reproduction

- `api-public-read` 재현에는 PostgreSQL, Redis, SQS queue URL(`CACHE_REFRESH_QUEUE_URL`, `READMODEL_REFRESH_QUEUE_URL`, `ENVIRONMENT_CACHE_REFRESH_QUEUE_URL`)이 필요합니다.
- `async-worker` 재현에는 Lambda event source mapping, `DB_*`, `REDIS_*`, 선택적 `METRICS_NAMESPACE`가 필요합니다.
- 현재 저장소만으로는 운영 환경이 복구되지 않으며, AWS 리소스 이름과 endpoint는 새로 발급해야 합니다.
