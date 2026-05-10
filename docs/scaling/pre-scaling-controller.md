## 1. 목표

---

재난 발생 시 `api-public-read-surge`를 선제 확장하여 Karpenter `public-surge` NodePool 생성을 유도한다.

```
재난 감지
→ pre-scaling controller가 HPA/api-public-read-surge minReplicas 조정
→ HPA가 api-public-read-surge replica 조정
→ Pending pod 발생
→ Karpenter가 public-surge node 생성
→ api-public-read Service가 base + surge pod로 트래픽 분산
```

---

## 2. 현재 배포 구조

### base read workload

```
Deployment/api-public-read
HPA/api-public-read
Node: app Managed Node Group
```

기존 HPA는 `Deployment/api-public-read`만 대상으로 한다. 현재 `hpa-api-public-read.yaml`의 `scaleTargetRef.name`은 `api-public-read`다.

### surge read workload

```
Deployment/api-public-read-surge
HPA/api-public-read-surge
Node: Karpenter public-surge NodePool
```

surge Deployment는 다음 label을 가진다.

```yaml
app: api-public-read-surge
service: api-public-read
component: surge
```

`Service/api-public-read`는 `service: api-public-read` selector로 base pod와 surge pod를 모두 받는다.

---

## 3. controller 책임

controller는 Deployment replica를 직접 계속 계산하지 않는다.

controller의 책임은 아래로 제한한다.

```
1. 재난 상태 감지
2. surge 필요 여부 판단
3. HPA/api-public-read-surge spec.minReplicas patch
4. cooldown 관리
5. 재난 해소 시 minReplicas 복구
6. controller 자체 상태/메트릭 노출 - 생략 가능
```

예시:

```bash
# 재난 발생
kubectl -n application patch hpa api-public-read-surge \
  --type merge \
  -p '{"spec":{"minReplicas":3}}'

# 재난 해소
kubectl -n application patch hpa api-public-read-surge \
  --type merge \
  -p '{"spec":{"minReplicas":0}}'
```

---

## 4. 중요한 전제 조건

### 4.1 ArgoCD ignoreDifferences 필요

ArgoCD dev Application은 automated sync와 selfHeal을 사용한다.

따라서 controller가 GitOps 관리 대상인 HPA의 `spec.minReplicas`를 patch하면 ArgoCD가 원래 값으로 되돌릴 수 있다.

필수 조치:

```yaml
ignoreDifferences:
  - group: autoscaling
    kind: HorizontalPodAutoscaler
    name: api-public-read-surge
    namespace: application
    jsonPointers:
      - /spec/minReplicas
```

가능하면 `RespectIgnoreDifferences=true` sync option도 함께 검토한다.

### 4.2 HPA `minReplicas: 0` 검증 필요

`minReplicas: 0`은 Kubernetes/HPA 설정에 따라 제한될 수 있다. 특히 CPU/Pods metric 기반 HPA는 scale-to-zero에 부적합할 수 있다.

따라서 EKS에서 아래를 실제 검증해야 한다.

```bash
kubectl apply --dry-run=server -f hpa-api-public-read-surge.yaml
```

거부되면 fallback은 아래 중 하나다.

```
A. minReplicas 1 유지 — 비용 증가
B. controller가 재난 시 minReplicas 1 이상으로 patch하고 해소 시 HPA/replicas 별도 처리
C. KEDA 또는 External metric 기반 scale-to-zero 구조로 확장
```

---

## 5. 입력 신호

1차 구현은 아래 중 하나로 한다.

| 입력 | 설명 | 추천 |
| --- | --- | --- |
| DB 조회 | `disaster_alert` 최신 상태/level_rank 기준 | 추천 |
| Redis 조회 | latest disaster/read model 기준 | 가능 |
| SQS event consume | ingestion event 기반 | 후속 개선 |
| public-read API 호출 | 자기 서비스 의존성 생김 | 비추천 |

MVP는 DB 또는 Redis polling 방식으로 충분하다.

---

## 6. 정책 예시

```yaml
preScaling:
  enabled: true
  namespace: application
  targetHpaName: api-public-read-surge
  targetDeploymentName: api-public-read-surge

  trigger:
    disasterTypes:
      - EARTHQUAKE
      - FLOOD
      - LANDSLIDE
    minLevelRank: 3
    lookbackMinutes: 10

  surge:
    normalMinReplicas: 0
    disasterMinReplicas: 3
    maxReplicas: 10
    cooldownSeconds: 1800
```

필수 정책 요소:

```
재난 유형
심각도 기준
최근성 기준
surge minReplicas
cooldown
maxReplicas 상한
재난 해소 조건
```

---

## 7. Kubernetes 권한

controller ServiceAccount는 최소한 아래 권한이 필요하다.

```yaml
apiGroups:
  - autoscaling
resources:
  - horizontalpodautoscalers
verbs:
  - get
  - patch
```

상태 확인까지 하려면 추가한다.

```yaml
apiGroups:
  - apps
resources:
  - deployments
verbs:
  - get
  - list

apiGroups:
  - ""
resources:
  - pods
verbs:
  - get
  - list
```

namespace는 `application`으로 제한한다.

---

## 8. custom metrics 전제

surge HPA는 custom metric 기반으로 간다.

예시 metric:

```yaml
metrics:
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "5"
```

필수 ops 의존성:

```
1. Prometheus Adapter 설치
2. custom.metrics.k8s.io API 활성화
3. http_requests_per_second metric mapping rule 구성
4. api-public-read pod metric scrape 확인
```

검증 명령:

```bash
kubectl get --raw \
  "/apis/custom.metrics.k8s.io/v1beta1/namespaces/application/pods/*/http_requests_per_second"
```

---

## 9. controller 관측 지표

controller도 Actuator/Micrometer 지표를 노출한다.

권장 metric:

```
prescaling_active
prescaling_target_min_replicas
prescaling_last_trigger_timestamp
prescaling_last_recover_timestamp
prescaling_k8s_patch_total
prescaling_k8s_patch_failed_total
prescaling_decision_total
```

로그에는 최소한 아래를 남긴다.

```
재난 감지 조건
기존 minReplicas
변경 minReplicas
cooldown 상태
Kubernetes patch 성공/실패
```

---

## 10. MVP 완료 기준

```
1. 재난 조건 감지 가능
2. HPA/api-public-read-surge minReplicas patch 가능
3. 재난 발생 시 0 → N 변경
4. 재난 해소 + cooldown 후 N → 0 변경
5. controller 재시작 후에도 현재 상태 재계산 가능
6. ArgoCD가 minReplicas patch를 되돌리지 않음
7. HPA가 custom metric을 읽는지 확인 가능
8. Pending surge pod 발생 시 Karpenter public-surge node 생성 확인
```

---

## 11. 구현 위치 제안

```
repo: safespot-application
service: services/pre-scaling-controller
```

Spring Boot 기반으로 구현하는 것을 권장한다.

필요 라이브러리:

```
io.fabric8:kubernetes-client
Spring Boot Actuator
Micrometer
DB 또는 Redis client
```

---

## 12. 최종 동작 흐름

```
평상시
- HPA/api-public-read-surge minReplicas=0
- Deployment/api-public-read-surge replicas=0
- public-surge node 없음

재난 발생
- controller가 조건 감지
- HPA minReplicas=3으로 patch
- HPA가 surge Deployment scale-out
- Karpenter가 node 생성
- Service/api-public-read가 base + surge pod로 트래픽 분산

재난 해소
- cooldown 이후 controller가 minReplicas=0으로 patch
- HPA가 surge pod 축소
- Karpenter consolidation으로 빈 node 정리
```

---

이제 다시 이어가면 다음 작업은 **manifest Phase 4 — ArgoCD Application finalization**이다.