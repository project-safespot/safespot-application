# safespot-application

Application repository for SafeSpot services.

---

## 문서 계약 요약

- RDS는 source of truth이며 Redis는 파생 read model만 저장한다.
- `external-ingestion`은 외부 API 수집, raw 보존, canonical 정규화, raw + canonical DB 저장, post-commit event/trigger를 담당한다.
- `async-worker`는 정규화된 DB 데이터에서 Redis read model을 재생성하고 `CacheRegenerationRequested`를 처리한다. raw 재분류는 하지 않는다.
- `api-public-read`는 Redis-first 공개 조회와 payload 기반 필터링을 담당한다. miss/stale/degraded case에서 `CacheRegenerationRequested`를 발행할 수 있지만 Redis `SET`/재생성은 하지 않는다.
- `api-public-read`의 direct RDS fallback은 degraded-mode 전용이며 target hot path가 아니다.

계약 문서의 읽기 순서는 `docs/README.md`를 따른다.

## 로컬 실행

### 사전 조건

- Java 21
- tmux

### 전체 서비스 시작

```bash
./deploy/local/run-all-local.sh
```

tmux 세션 `safespot-local`이 생성되고 창 4개가 자동으로 실행된다.

```bash
tmux attach -t safespot-local   # 세션 접속
```

### 포트 정보

| 서비스 | 포트 | H2 Console |
|--------|------|------------|
| api-core | 8080 | http://localhost:8080/h2-console |
| api-public-read | 8081 | http://localhost:8081/h2-console |
| external-ingestion | 8082 | http://localhost:8082/h2-console |

### 전체 서비스 종료

```bash
./deploy/local/stop-all-local.sh
```

### 단일 서비스 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :services:api-core:bootRun
SPRING_PROFILES_ACTIVE=local ./gradlew :services:api-public-read:bootRun
SPRING_PROFILES_ACTIVE=local ./gradlew :services:external-ingestion:bootRun
```

### tmux 단축키

| 키 | 동작 |
|----|------|
| `Ctrl-b + w` | 창 목록 |
| `Ctrl-b + 0~3` | 창 번호로 이동 |
| `Ctrl-b + d` | 세션 detach |
