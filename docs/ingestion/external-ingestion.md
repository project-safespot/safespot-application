# 외부 API 수집 스케줄링 및 데이터 정규화 서비스 설계서

---

## 1. 목적 및 범위

본 문서는 SafeSpot 재난안전대피 서비스에서 사용되는 외부 API 10개의 데이터 수집 스케줄링과 정규화를 담당하는 external-ingestion 마이크로서비스의 상세 설계를 기술한다. 이 서비스는 AWS EKS 환경에서 실행되며, 최종 RDS v6 및 SafeSpot REST API 최종본을 기준으로 설계된다. 설계 범위는 다음과 같다.

- 외부 API 10개의 수집 방식 분류 및 호출 주기 정의
- 상시 실행 Deployment / Kubernetes CronJob 혼합 방식 정의
- Ingestion Pod의 원본 수집 및 저장 설계
- 정규화 결과 적재 대상 테이블 및 로직 정의
- 캐시 갱신 이벤트 발행 및 async+worker 영역 인터페이스 정의
- 재난 등급 기반 선제 스케일링 컨트롤러 설계
- 보안(Secrets/IAM) 및 운영 고려사항

---

## 2. 외부 API 목록 및 수집 방식 분류

### 2.1 전체 API 목록

외부 API 10개는 수집 방식에 따라 세 가지로 분류한다.

**그룹 A-1 — 재난 감지 (상시 실행 Deployment, polling loop)**

호출 주기가 짧고 상시 감시가 필요한 API. Scheduler Pod 없이 Ingestion Deployment 내부 polling loop로 주기를 직접 관리한다.

| source_code | API명 | 제공기관 | 호출 제한 | 폴링 주기 | 정규화 대상 | Worker 작업명 |
| --- | --- | --- | --- | --- | --- | --- |
| `SAFETY_DATA_ALERT` | 재난문자 | 행정안전부 | 1,000회/일 | **2분** | `disaster_alert` | `CollectSafetyDataAlerts` |
| `KMA_EARTHQUAKE` | 지진 정보 | 기상청 | 10,000회/일 (개발) | 1분 | `disaster_alert` + `disaster_alert_detail` | `CollectKMAEarthquake` |
| `SEOUL_EARTHQUAKE` | 서울시 지진 발생 현황 | 서울시 | 제한 없음 | **30초** | `disaster_alert` | `CollectSeoulEarthquake` |
| `FORESTRY_LANDSLIDE` | 산사태 위험 예측 | 산림청 | 10,000회/일 (개발) | 5분 | `disaster_alert` | `CollectForestryLandslide` |
| `SEOUL_RIVER_LEVEL` | 하천 수위 | 서울시 | 제한 없음 | **30초** | `disaster_alert` | `CollectSeoulRiverLevel` |

> `SAFETY_DATA_ALERT`는 일일 호출 한도 1,000회 초과 방지를 위해 2분 주기로 설정한다. 1분 주기 시 일일 1,440회로 한도를 초과한다.
> 
> 
> `FORESTRY_LANDSLIDE`는 산림청 인증키 승인 대기 중이며, 승인 완료 전까지 polling loop를 비활성 상태로 유지한다.
> 

**그룹 A-2 — 환경 정보 (CronJob)**

수집 주기가 길어 CronJob으로 처리한다.

| source_code | API명 | 제공기관 | 호출 제한 | 폴링 주기 | 정규화 대상 | Worker 작업명 |
| --- | --- | --- | --- | --- | --- | --- |
| `KMA_WEATHER` | 날씨 단기예보 | 기상청 | 10,000회/일 (개발) | 1시간 | `weather_log` | `CollectKMAWeather` |
| `AIR_KOREA_AIR_QUALITY` | 대기질 | 에어코리아 | 500회/일 (개발) | 1시간 | `air_quality_log` | `CollectAirKoreaAirQuality` |

> `AIR_KOREA_AIR_QUALITY`는 개발 계정 일일 500회 한도를 고려하여 1시간 주기(하루 24회)로 유지한다. 운영 계정 전환 전 주기를 단축하지 않는다.
> 

**그룹 B — 대피소 마스터 (CronJob + 초기 배치)**

갱신 주기가 비실시간이거나 파일 형태인 API. CronJob으로 관리하고 초기 데이터 적재는 배치 스크립트로 별도 수행한다. Scheduler Pod 없이 Kubernetes CronJob 리소스로 직접 관리한다.

| source_code | API명 | 제공기관 | 폴링 주기 | 정규화 대상 | Worker 작업명 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| `SEOUL_SHELTER_EARTHQUAKE` | 서울시 지진옥외대피소 | 서울시 | 1일 1회 | `shelter` (selective upsert) | `CollectSeoulEarthquakeShelter` | 초기 배치 병행 |
| `SEOUL_SHELTER_LANDSLIDE` | 서울시 산사태 대피소 | 서울시 | 1일 1회 | `shelter` (selective upsert) | `CollectSeoulLandslideShelter` | 초기 배치 병행 |
| `SEOUL_SHELTER_FLOOD` | 서울시 수해 대피소 | 서울시 | 배치 전용 | `shelter` (selective upsert) | `LoadSeoulFloodShelter` | xlsx 파일 형태, CronJob 미생성 |

### 2.2 지진 중복 수집 처리 정책

`KMA_EARTHQUAKE`(기상청)와 `SEOUL_EARTHQUAKE`(서울시)는 동일 지진 이벤트를 각각 수집할 수 있다. `disaster_alert.source` 컬럼에 각 source_code를 기록하여 두 소스를 구분하며, 중복 여부와 관계없이 둘 다 저장한다. 조회 API(`GET /disasters/{disasterType}/latest`)는 `issued_at` 기준 최신 1건을 반환하므로 운영상 문제는 없다.

---

## 3. 시스템 아키텍처

### 3.1 상위 아키텍처 개요

external-ingestion 서비스는 수집 방식에 따라 두 가지 실행 패턴으로 구성된다. Scheduler Pod는 존재하지 않으며, 재난 감지 그룹은 상시 실행 Deployment가 polling loop로 직접 수집하고, 환경 정보 및 대피소 마스터는 Kubernetes CronJob이 담당한다.

```
┌──────────────────────────────────────────────────────────────────┐
│ AWS EKS Cluster (compute 영역)                                    │
│                                                                  │
│  ┌──────────────────────────┐  ┌───────────────────────────────┐  │
│  │  Ingestion Deployment    │  │  CronJob                      │  │
│  │  (그룹 A-1, 상시 실행)    │  │  (그룹 A-2 환경 / 그룹 B 대피소) │  │
│  │  polling loop 내장        │  │  Kubernetes 네이티브 관리      │  │
│  └─────────────┬────────────┘  └──────────────┬────────────────┘  │
│                │                              │                   │
│                └──────────────┬───────────────┘                   │
│                               │ SQS (정규화 큐)                    │
│                   ┌───────────▼────────────┐                      │
│                   │   Normalizer Pods      │                      │
│                   │   (상시 실행 Deployment) │                      │
│                   └───────────┬────────────┘                      │
│                               │                                   │
│  ┌────────────────────────────▼──────────────────────────────┐    │
│  │  RDS (compute 영역 read/write 담당)                         │    │
│  │  disaster_alert / disaster_alert_detail                   │    │
│  │  weather_log / air_quality_log / shelter                  │    │
│  │  external_api_* (소스·스케줄·이력·원본·오류)                  │    │
│  └────────────────────────────┬──────────────────────────────┘    │
│                               │ SQS 캐시 갱신 이벤트 발행           │
│  ┌────────────────────────────┐                                   │
│  │  disaster-scale-controller │ ← 재난 등급 기반 선제 스케일링        │
│  │  (경량 컨트롤러 Deployment)  │   HPA minReplicas 동적 조정         │
│  └────────────────────────────┘                                   │
└───────────────────────────────│──────────────────────────────────┘
                                │
        ┌───────────────────────▼──────────────────────┐
        │  async+worker 영역 (SQS + Lambda)              │
        │  CacheRefreshWorker                           │
        │  ※ 본 문서 범위 밖. 담당: async+worker 영역    │
        └───────────────────────┬──────────────────────┘
                                │
        ┌───────────────────────▼──────────────────────┐
        │  cache 영역 (Redis / ElastiCache)              │
        │  disaster:active:{region}                     │
        │  env:weather:{nx}:{ny}                        │
        │  env:air:{station_name}                       │
        └──────────────────────────────────────────────┘
```

- **Ingestion Deployment (그룹 A-1)**: 상시 실행. 소스별 polling loop를 내장하여 외부 API를 호출하고, 원본 응답을 `external_api_raw_payload`에 저장한 뒤 정규화 SQS 큐에 메시지를 투입한다.
- **CronJob (그룹 A-2, 그룹 B)**: Kubernetes CronJob 리소스로 직접 관리. 실행 시 동일한 Ingestion 처리 흐름을 수행한다.
- **Normalizer Pods**: 상시 실행 Deployment. 정규화 SQS 큐를 구독하여 원본 데이터를 RDS에 적재하고 캐시 갱신 이벤트를 발행한다.
- **disaster-scale-controller**: 경량 컨트롤러 Deployment. `disaster_alert` 테이블을 polling하여 재난 등급 기반으로 HPA `minReplicas`를 동적 조정한다.
- **Async Worker (SQS + Lambda)**: async+worker 영역 담당. SQS 이벤트 수신 후 Redis 갱신. **external-ingestion 담당 범위 밖.**

### 3.2 네트워크 전제조건

EKS 클러스터는 network 영역에서 관리하는 프라이빗 서브넷에 배치된다. compute 영역은 network 영역의 output을 input으로 받아 사용하며 직접 네트워크 리소스를 생성하지 않는다.

| network 영역 output | 현재 값 | 비고 |
| --- | --- | --- |
| `vpc_id` | safespot-dev-network-vpc-main | 10.0.0.0/16 |
| `private_subnet_ids` | subnet-private-2a, subnet-private-2c | 10.0.11.0/24, 10.0.12.0/24 |
| NAT Gateway | 현재 비활성 | 서비스 배포 전 활성화 필요 |

> **주의**: NAT Gateway가 활성화되기 전까지 Ingestion Pod의 외부 API 호출이 불가능하다. 서비스 배포 전 network 영역 담당자(jaeyoung)와 NAT Gateway 활성화 일정을 협의해야 한다.
> 

### 3.3 보안

- **Secrets 관리**: API Key, DB 접속 정보 등 민감한 설정은 AWS Secrets Manager에 저장하고 IRSA를 통해 Pod 환경 변수로 주입한다. 산림청 인증키는 승인 완료 후 등록한다.
- **IAM 역할**: 각 Pod는 필요한 리소스에만 접근하도록 IAM Role for Service Account(IRSA)를 사용한다.
- **스케일링 컨트롤러 RBAC**: `disaster-scale-controller`는 HPA 수정 권한만 부여하는 최소 권한 ClusterRole을 별도로 정의한다.

---

## 4. 수집 설계

### 4.1 그룹 A-1 Ingestion Deployment (상시 실행)

소스별 polling loop를 단일 Deployment에 내장한다. 각 소스는 독립적인 goroutine(또는 thread)으로 실행되며 서로 영향을 주지 않는다.

- **공통 인터페이스**: 모든 Worker는 공통 `IngestionHandler` 인터페이스를 구현한다. 주요 메소드는 `prepareRequest()`, `callExternal()`, `writeRawPayload()`, `publishNormalizationTask()`.
- **Rate Limit 대응**: `SAFETY_DATA_ALERT`(1,000회/일) 등 호출 제한이 있는 API는 polling loop 내에서 일일 카운터를 관리하여 한도 초과 시 해당 주기를 SKIP하고 `execution_log`에 기록한다.
- **에러 처리 및 재시도**: 네트워크 오류 또는 5xx 응답 시 최대 3회 재시도(지수 백오프 적용) 후 `FAILED`로 기록하고 알람을 발송한다.
- **원본 저장**: 응답 JSON은 `external_api_raw_payload.response_body(JSONB)`에 저장하고, `payload_hash`로 중복 수집 여부를 확인한다. 원본 보존 기간은 90일(`retention_expires_at = collected_at + 90일`).
- **정규화 큐 투입**: 원본 저장 완료 후 `raw_id`를 포함한 메시지를 정규화 SQS 큐에 투입한다.

### 4.2 그룹 A-2 / 그룹 B CronJob

Kubernetes CronJob 리소스로 직접 관리한다. `external_api_schedule` 테이블은 그룹 A-1 polling loop의 주기 설정 참조용으로만 유지하며, CronJob 주기는 Kubernetes 매니페스트에서 직접 정의한다.

### CronJob 주기 정의

| source_code | cron_expr | 설명 |
| --- | --- | --- |
| `KMA_WEATHER` | `0 * * * *` | 매시 정각 |
| `AIR_KOREA_AIR_QUALITY` | `0 * * * *` | 매시 정각 |
| `SEOUL_SHELTER_EARTHQUAKE` | `0 2 * * *` | 매일 새벽 2시 |
| `SEOUL_SHELTER_LANDSLIDE` | `0 2 * * *` | 매일 새벽 2시 |
| `SEOUL_SHELTER_FLOOD` | — | 배치 전용, CronJob 미생성 |

### 그룹 B 초기 배치 전제

대피소 마스터 CronJob이 최초 실행되기 전에 배치 스크립트로 `shelter` 테이블을 미리 적재해야 한다. 초기 적재 없이 시스템이 개시되면 최초 사용자 조회 시 RDS fallback이 빈 결과를 반환한다.

---

## 5. 데이터 정규화 설계

### 5.1 정규화 대상 테이블 및 적재 방식

| source_code | 정규화 대상 테이블 | 적재 방식 | 비고 |
| --- | --- | --- | --- |
| `SAFETY_DATA_ALERT` | `disaster_alert` | INSERT (source+issued_at 기준 중복 skip) |  |
| `KMA_EARTHQUAKE` | `disaster_alert` + `disaster_alert_detail` | INSERT + INSERT | `detail_type=EARTHQUAKE`, 이벤트 1개 통합 발행 |
| `SEOUL_EARTHQUAKE` | `disaster_alert` | INSERT | `source=SEOUL_EARTHQUAKE` |
| `FORESTRY_LANDSLIDE` | `disaster_alert` | INSERT | `detail_json`에 위험 등급 저장 가능 |
| `SEOUL_RIVER_LEVEL` | `disaster_alert` | INSERT | `detail_json`에 수위 정보 저장 가능 |
| `KMA_WEATHER` | `weather_log` | INSERT (UNIQUE 제약 기반 중복 방지) |  |
| `AIR_KOREA_AIR_QUALITY` | `air_quality_log` | INSERT (UNIQUE 제약 기반 중복 방지) |  |
| `SEOUL_SHELTER_EARTHQUAKE` | `shelter` | Selective upsert | 허용 컬럼만 갱신 |
| `SEOUL_SHELTER_LANDSLIDE` | `shelter` | Selective upsert | 허용 컬럼만 갱신 |
| `SEOUL_SHELTER_FLOOD` | `shelter` | Selective upsert (배치) | 허용 컬럼만 갱신 |

### 5.2 정규화 대상 테이블 구조 요약

### `disaster_alert`

재난 감지 5개 API의 공통 적재 대상. `source` 컬럼으로 수집 출처를 구분한다.

주요 컬럼: `alert_id`, `disaster_type`, `region`, `level`, `message`, `source`, `issued_at`, `expired_at`

`disaster_type` CHECK 제약: `EARTHQUAKE` / `FLOOD` / `LANDSLIDE`

### `disaster_alert_detail`

`KMA_EARTHQUAKE` 수집 시 `disaster_alert`와 1:1로 생성. `detail_type` 컬럼으로 재난 유형을 구분하고, 유형별 확장 필드는 `detail_json(JSONB)`으로 처리한다.

주요 컬럼: `detail_id`, `alert_id(FK)`, `detail_type`, `magnitude`, `epicenter`, `intensity`, `detail_json`

`detail_json` 활용 예시:

```json
// FLOOD
{ "river_level": 4.32, "danger_level": "주의", "station": "한강대교" }

// LANDSLIDE
{ "risk_grade": "3등급", "predicted_area": "관악구 신림동" }
```

### `weather_log`

`KMA_WEATHER` 수집 결과 적재. 격자 좌표(`nx`, `ny`) 기준 저장.

주요 컬럼: `nx`, `ny`, `base_date`, `base_time`, `forecast_dt`, `tmp`, `sky`, `pty`, `pop`, `pcp`, `wsd`, `reh`, `collected_at`

UNIQUE 제약: `(nx, ny, base_date, base_time, forecast_dt)`

### `air_quality_log`

`AIR_KOREA_AIR_QUALITY` 수집 결과 적재. 측정소명 기준 저장.

주요 컬럼: `station_name`, `measured_at`, `pm10`, `pm10_grade`, `pm25`, `pm25_grade`, `o3`, `khai_value`, `khai_grade`, `collected_at`

UNIQUE 제약: `(station_name, measured_at)`

### `shelter` (selective upsert)

그룹 B 3개 API 적재 대상. 외부 API가 갱신 가능한 컬럼은 `name`, `shelter_type`, `disaster_type`, `address`, `latitude`, `longitude`, `capacity`로 제한한다. `manager`, `contact`, `shelter_status`, `note`는 내부 관리자 전용 컬럼으로 외부 수집이 덮어쓰지 않는다.

### 5.3 Normalizer Pod 처리 흐름

1. **메시지 수신**: 정규화 SQS 큐에서 메시지를 수신한다. 메시지에는 `raw_id`, `source_id`, `execution_id`가 포함된다.
2. **원본 조회**: `external_api_raw_payload`에서 `raw_id` 기준으로 원본 데이터를 조회한다.
3. **파싱 및 검증**: JSON을 파싱하여 필수 필드 존재 여부와 데이터 타입을 검증한다. 검증 실패 시 `external_api_normalization_error`에 `raw_fragment`와 함께 기록하고 해당 레코드는 건너뛴다.
4. **필드 변환**: 날짜 필드는 ISO 8601 형식으로, 수치 단위는 정의된 기준으로 변환한다.
5. **RDS 적재**: 대상 테이블에 upsert 또는 INSERT한다. `shelter`는 허용 컬럼만 selective upsert. **compute 영역의 책임은 여기까지.**
6. **캐시 갱신 이벤트 발행**: RDS 적재 완료 후 SQS로 캐시 갱신 이벤트를 발행한다. Redis 갱신은 async+worker 영역이 담당하며 Normalizer Pod는 직접 Redis에 접근하지 않는다.
7. **실행 이력 업데이트**: `external_api_execution_log`의 `records_normalized`, `records_failed`를 업데이트하고 `execution_status`를 확정한다.

---

## 6. 캐시 갱신 이벤트 명세 (async+worker 영역 인터페이스)

Redis 갱신은 async+worker 영역(SQS + Lambda)이 담당한다. Normalizer Pod는 RDS 적재 완료 후 SQS로 이벤트만 발행한다. 아래 명세는 async+worker 영역 담당자에게 전달하는 인터페이스 정의다.

### 6.1 역할 경계

| 단계 | 담당 영역 | 내용 |
| --- | --- | --- |
| RDS 적재 | compute (Normalizer Pod) | 정규화 결과를 RDS 대상 테이블에 upsert/INSERT |
| 이벤트 발행 | compute (Normalizer Pod) | RDS 적재 완료 후 SQS로 캐시 갱신 이벤트 발행 |
| Redis 갱신 | async+worker (Lambda) | SQS 이벤트 수신 후 Redis SET |

### 6.2 SQS 큐 구조

종류별 별도 큐로 구성한다 (잠정 — async+worker 영역 담당자 확정 후 반영).

| 이벤트 유형 | SQS 큐 이름 (잠정) |
| --- | --- |
| 재난 알림 / 상세 | `safespot-dev-async-worker-sqs-disaster-cache` |
| 날씨 예보 | `safespot-dev-async-worker-sqs-weather-cache` |
| 대기질 | `safespot-dev-async-worker-sqs-air-cache` |

### 6.3 이벤트 페이로드

REST API 명세서의 이벤트 envelope 형식을 동일하게 적용한다.

**DisasterAlertCacheRefresh** — `disaster_alert` 및 `disaster_alert_detail` 적재 완료 후 1개 이벤트 통합 발행

```json
{
  "eventId": "uuid-v4",
  "eventType": "DisasterAlertCacheRefresh",
  "occurredAt": "2026-04-19T10:00:00+09:00",
  "version": 1,
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "alert:{alertId}:CACHE_REFRESH:v1",
  "payload": {
    "alertId": 55,
    "region": "서울특별시",
    "disasterType": "EARTHQUAKE"
  }
}
```

**WeatherCacheRefresh**

```json
{
  "eventId": "uuid-v4",
  "eventType": "WeatherCacheRefresh",
  "occurredAt": "2026-04-19T10:00:00+09:00",
  "version": 1,
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "weather:{nx}:{ny}:CACHE_REFRESH:v1",
  "payload": {
    "nx": 60,
    "ny": 127
  }
}
```

**AirQualityCacheRefresh**

```json
{
  "eventId": "uuid-v4",
  "eventType": "AirQualityCacheRefresh",
  "occurredAt": "2026-04-19T10:00:00+09:00",
  "version": 1,
  "producer": "external-ingestion",
  "traceId": "uuid-v4",
  "idempotencyKey": "air:{stationName}:CACHE_REFRESH:v1",
  "payload": {
    "stationName": "종로구"
  }
}
```

### 6.4 대피소 마스터 캐시 갱신 정책

그룹 B 대피소 마스터 수집 완료 시 별도 캐시 갱신 이벤트를 발행하지 않는다. `shelter:list` 캐시는 TTL 만료 시 api-public-read의 RDS fallback → Redis SET으로 자연 갱신된다. 초기 시스템 개시 시 Redis 키가 없는 상태에서도 최초 조회 시 RDS fallback이 자동으로 동작하므로 서비스 공백 없이 정상 제공된다.

---

## 7. 재난 등급 기반 선제 스케일링

### 7.1 개요

재난 등급이 `경계` 이상으로 상승하면 api-public-read HPA의 `minReplicas`를 선제적으로 상향하여 트래픽 급증에 대비한다. HPA 반응 지연 + Karpenter 노드 프로비저닝 시간(60~90초)을 사전에 해소하는 것이 목적이다.

### 7.2 구현 구조

별도 경량 컨트롤러 Pod(`disaster-scale-controller`)로 분리하여 구현한다. Normalizer와 책임을 분리하고 HPA 수정 권한을 최소화된 RBAC으로 격리한다.

```
disaster_alert 테이블 polling (30초 주기)
  → level IN ('경계', '심각') 이고 expired_at IS NULL 인 활성 재난 감지
  → Kubernetes HPA API 호출
  → api-public-read HPA minReplicas 상향
  → Karpenter 선제 노드 프로비저닝

expired_at 갱신 감지 시
  → minReplicas 즉시 원복 (평상시 값으로)
```

### 7.3 트리거 및 원복 조건

| 조건 | 동작 |
| --- | --- |
| `level IN ('경계', '심각')` 이고 `expired_at IS NULL` 인 재난 존재 | minReplicas 상향 |
| 해당 재난의 `expired_at` 갱신 감지 (활성 재난 없는 상태) | minReplicas 즉시 원복 |

### 7.4 minReplicas 조정 수치

노드 스펙 확정 후 결정 (TBD). 평상시 / 경계 / 심각 3단계로 구분하여 설정할 예정.

### 7.5 RBAC 설계

`disaster-scale-controller` ServiceAccount에 아래 최소 권한만 부여한다.

```yaml
rules:
-apiGroups:["autoscaling"]
resources:["horizontalpodautoscalers"]
verbs:["get","patch"]
resourceNames:["api-public-read-hpa"]
```

### 7.6 부하테스트 검증

운영 검증 기간(10일) 중 ops 영역 담당자와 협의하여 부하테스트 시나리오를 구성한다.

검증 목표: 선제 스케일링 적용 시 vs 미적용 시 트래픽 급증 구간의 응답 지연 차이 수치화.

---

## 8. 운영 이력 관리

수집 실행 이력과 정규화 실패 이력은 별도 테이블로 분리하여 관리한다. 테이블 상세 구조는 external-ingestion RDS 설계 문서 v2를 참조한다.

- `external_api_execution_log`: 실행별 성공/실패/건수 기록. 보존 180일 이상 권장.
- `external_api_raw_payload`: 원본 응답 보관. 보존 90일 (`retention_expires_at` 기준).
- `external_api_normalization_error`: 정규화 실패 건 추적. `resolved` 플래그로 조치 여부 관리.

---

## 9. 배포 및 운영

### 9.1 Kubernetes 리소스

- **Deployment (그룹 A-1 Ingestion)**: 상시 실행. 고정 레플리카로 운영. 수평 확장 시 중복 수집 방지를 위해 소스별 분산 락 또는 파티셔닝 적용 필요.
- **Deployment (Normalizer)**: 상시 실행. HPA를 설정하여 SQS 정규화 큐 길이 기준으로 Pod 수를 자동 조정.
- **CronJob (그룹 A-2, 그룹 B)**: Kubernetes 네이티브 CronJob 리소스로 직접 관리. `concurrencyPolicy: Forbid` 설정.
- **Deployment (disaster-scale-controller)**: 상시 실행. 단일 레플리카. MNG-base 노드에 고정 배치.
- **Secret**: API Key, DB 패스워드 등 민감한 값은 AWS Secrets Manager에 저장하고 IRSA로 주입한다.

### 9.2 EKS 배치 노드

external-ingestion 전체 워크로드(Ingestion Deployment, Normalizer Pods, CronJob, disaster-scale-controller)는 MNG-base 노드에 배치한다. Spot 인터럽션으로 인한 수집 중단을 방지하기 위해 온디맨드 인스턴스를 사용한다.

### 9.3 네이밍 규칙

프로젝트 네이밍 규칙 `{project}-{env}-{domain}-{resource_type}-{name}`을 따른다.

| 리소스 | 이름 예시 |
| --- | --- |
| Ingestion Deployment | `safespot-dev-api-service-deploy-ingestion` |
| Normalizer Deployment | `safespot-dev-api-service-deploy-normalizer` |
| Scale Controller Deployment | `safespot-dev-api-service-deploy-scale-controller` |
| CronJob (날씨) | `safespot-dev-api-service-cronjob-weather` |

### 9.4 모니터링 및 알람

- **모니터링**: CloudWatch Container Insights와 Prometheus Exporter를 통해 Pod 상태, API 호출 지연, SQS 큐 길이, 정규화 오류율을 수집한다. 모니터링 인프라 구성은 ops 영역 담당.
- **알람 대상 분류**:
    - 높은 우선순위: `SAFETY_DATA_ALERT`, `KMA_EARTHQUAKE`, `SEOUL_EARTHQUAKE`, `SEOUL_RIVER_LEVEL` 실패
    - 낮은 우선순위: `KMA_WEATHER`, `AIR_KOREA_AIR_QUALITY`, 그룹 B 대피소 수집 실패
    - 알람 제외: `FORESTRY_LANDSLIDE` (승인 완료 전)
- **알람 채널**: CloudWatch Alarm → SNS → Slack

### 9.5 운영 고려사항

- **NAT Gateway 전제**: 서비스 배포 전 network 영역 담당자(jaeyoung)와 NAT Gateway 활성화 일정 협의 필요.
- **산림청 인증키 승인 완료 시**: Secrets Manager에 인증키 등록 → polling loop 활성화 → 알람 대상 추가 순서로 처리.
- **백업 및 복구**: RDS 스냅샷 자동 백업으로 정규화 결과를 보존한다. Redis 캐시 장애 시 RDS 데이터로부터 재구축한다.

---

## 10. Observability / Monitoring

### 10.1 공통 원칙

- Ingestion Deployment 및 Normalizer Deployment는 모두 Prometheus scrape 대상이다.
- `/actuator/prometheus` endpoint를 Pod 단위로 노출한다.
- 공통 레이블 (모든 metric에 적용):

| 레이블 | 값 |
| --- | --- |
| `service` | `external-ingestion` |
| `env` | `dev` / `prod` |
| `region` | `seoul` |
- metric naming prefix 규칙: 모든 metric은 `ingestion_` prefix를 사용한다.
- trace_id 정책:
    - `trace_id`는 수집 실행 1회 단위로 생성한다.
    - 원본 저장(raw payload) → 정규화 → SQS publish 전 구간에 `trace_id`를 전파한다.
    - 로그 출력 시 `trace_id`를 반드시 포함한다.
- 로그 정책:
    - 수집한 raw payload 전체를 애플리케이션 로그에 출력하지 않는다.
    - raw payload 원본은 `external_api_raw_payload` 테이블에 저장하며, 보존 기간은 90일이다.

---

### 10.2 메트릭 정의

**Polling Loop 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_polling_loop_iteration_total` | counter | `service`, `source` | polling loop 1회 실행이 시작될 때 |
| `ingestion_polling_loop_skipped_total` | counter | `service`, `source`, `reason` | rate limit 한도 도달 또는 기타 사유로 해당 주기를 SKIP할 때 |

> `ingestion_polling_loop_skipped_total`의 `reason` label 허용 값:
> 

| reason 값 | 설명 |
| --- | --- |
| `rate_limit` | 일일 호출 카운터가 한도에 도달하여 SKIP |
| `disabled` | 소스가 명시적으로 비활성 상태 (예: `FORESTRY_LANDSLIDE` 승인 대기 중) |
| `error` | polling loop 내부 오류로 인해 해당 주기 SKIP |

**외부 API 호출 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_external_api_call_total` | counter | `service`, `source` | 외부 API HTTP 요청 전송 직전 |
| `ingestion_external_api_failure_total` | counter | `service`, `source`, `type` | 외부 API 응답이 오류로 처리될 때 |
| `ingestion_external_api_latency_seconds` | histogram | `service`, `source` | 외부 API 응답 수신 완료 시 (HTTP 요청 전송 직전 ~ 응답 수신 기준) |

> `ingestion_external_api_failure_total`의 `type` label 허용 값:
> 

| type 값 | 설명 |
| --- | --- |
| `network` | DNS 실패, 연결 거부 등 네트워크 수준 오류 |
| `timeout` | 응답 대기 시간 초과 |
| `client_error` | HTTP 4xx 응답 |
| `server_error` | HTTP 5xx 응답 |

**Rate Limit / Retry 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_external_api_rate_limit_exceeded_total` | counter | `service`, `source` | polling loop 내 일일 카운터가 한도에 도달하여 해당 주기를 SKIP할 때 |
| `ingestion_external_api_retry_total` | counter | `service`, `source` | 재시도 로직이 실행될 때 (최대 3회, 지수 백오프 적용 — 재시도 1회당 1 증가) |

**수집 구간 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_total_fetch_duration_seconds` | histogram | `service`, `source` | 외부 API 호출 직전 ~ raw payload DB 저장 완료 기준 (latency + DB write 포함, 수집 구간 전체 측정) |
| `ingestion_disaster_alert_received_total` | counter | `service`, `source` | 외부 API 응답 raw payload 내 재난 이벤트 1건 파싱 완료 시 (dedup·중복 skip 이전 raw 기준, 재난 감지 그룹 A-1에만 적용) |

**정규화 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_normalization_duration_seconds` | histogram | `service`, `source` | SQS 메시지 수신 ~ RDS 적재 완료 기준 (정규화 구간만 측정) |
| `ingestion_normalization_success_total` | counter | `service`, `source` | 정규화 결과가 RDS 대상 테이블에 정상 적재될 때 |
| `ingestion_normalization_failure_total` | counter | `service`, `source`, `reason` | 정규화 처리 중 실패 시 |

> `ingestion_normalization_failure_total`의 `reason` label 허용 값:
> 

| reason 값 | 설명 |
| --- | --- |
| `parse_error` | raw payload 파싱 실패 |
| `validation_error` | 정규화 결과 유효성 검증 실패 |
| `db_error` | RDS 적재 실패 |

**SQS Publish 메트릭**

| metric name | type | labels | 발생 시점 |
| --- | --- | --- | --- |
| `ingestion_sqs_publish_total` | counter | `service`, `source` | 캐시 갱신 이벤트 SQS 전송 성공 시 |
| `ingestion_sqs_publish_failure_total` | counter | `service`, `source` | 캐시 갱신 이벤트 SQS 전송 실패 시 |

---

### 10.3 처리 단계별 계측 지점

외부 API 수집 1회 실행 흐름에서 각 단계의 계측 지점을 아래와 같이 정의한다.

| 단계 | 처리 내용 | 계측 대상 메트릭 |
| --- | --- | --- |
| 0. polling loop 실행 | polling loop 1회 시작 | `ingestion_polling_loop_iteration_total` 증가 |
| 1. 외부 API 호출 직전 | HTTP 요청 전송 전 | `ingestion_external_api_call_total` 증가, `ingestion_total_fetch_duration_seconds` 측정 시작 |
| 2. 응답 수신 | HTTP 응답 수신 완료 | `ingestion_external_api_latency_seconds` 기록 |
| 3. 응답 오류 | 네트워크 오류 / timeout / 4xx / 5xx 감지 시 | `ingestion_external_api_failure_total{type}` 증가 |
| 4. Rate Limit 감지 | 일일 카운터가 한도 도달로 SKIP 처리 시 | `ingestion_external_api_rate_limit_exceeded_total` 증가, `ingestion_polling_loop_skipped_total{reason=rate_limit}` 증가 |
| 4-b. 소스 비활성 / 내부 오류 SKIP | disabled 또는 오류로 SKIP 처리 시 | `ingestion_polling_loop_skipped_total{reason=disabled\|error}` 증가 |
| 5. 재시도 실행 | 지수 백오프 재시도 1회 실행 시 | `ingestion_external_api_retry_total` 증가 |
| 6. Raw payload 저장 | `external_api_raw_payload` 테이블 저장 완료 | `ingestion_total_fetch_duration_seconds` 기록 완료 |
| 7. 정규화 결과 분기 | RDS 적재 성공 / 실패 분기 | `ingestion_normalization_success_total` 또는 `ingestion_normalization_failure_total{reason}` 증가, `ingestion_normalization_duration_seconds` 기록 |
| 8. SQS publish | 캐시 갱신 이벤트 SQS 전송 | 성공 시 `ingestion_sqs_publish_total` 증가, 실패 시 `ingestion_sqs_publish_failure_total` 증가 |

---

### 10.4 scrape 대상 명시

- Ingestion Deployment, Normalizer Deployment 모두 Pod 단위로 Prometheus scrape 대상에 등록한다.
- Pod에는 아래 annotation을 부여한다.

```yaml
annotations:
prometheus.io/scrape:"true"
prometheus.io/port:"8080"
prometheus.io/path:"/actuator/prometheus"
```

- CronJob으로 실행되는 Pod는 실행 시간이 짧으므로 scrape 대상에서 제외한다. CronJob 실행 결과는 `external_api_execution_log` 테이블 기록으로 추적한다.

---

### 10.5 HPA 연계 확장 힌트

현재 구현 범위에 포함되지 않으나, 아래 메트릭은 향후 HPA 기준으로 활용할 수 있다.

| 메트릭 | 활용 가능 시나리오 |
| --- | --- |
| `ingestion_external_api_failure_total` / `ingestion_external_api_call_total` (비율) | 외부 API 실패율 급증 시 Normalizer Pod 스케일 조정 |
| `ingestion_total_fetch_duration_seconds` (p95) | 수집 지연 감지 시 Ingestion Pod 스케일 조정 |
| `ingestion_external_api_retry_total` | 재시도 누적 증가 시 장애 선행 지표로 활용 |

---

## 11. 결론

본 설계서는 외부 API 10개에 대한 수집 스케줄링 및 정규화를 AWS EKS compute 영역에서 구현하기 위한 설계를 제시한다. 재난 감지 그룹은 상시 실행 Deployment의 polling loop로, 환경 정보 및 대피소 마스터는 Kubernetes CronJob으로 처리하는 혼합 구조를 채택하였다. Scheduler Pod를 제거하고 CronJob을 Kubernetes 네이티브로 직접 관리함으로써 구조를 단순화하였으며, Redis 갱신 책임을 async+worker 영역으로 명확히 위임하였다. 재난 등급 기반 선제 스케일링 컨트롤러를 별도 경량 Pod로 분리하여 compute 영역 내에서 관리하며, 운영 검증 기간 중 ops 영역과 협력하여 부하테스트로 효과를 수치화한다.