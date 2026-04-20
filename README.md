# safespot-application

Application repository for SafeSpot services.

---

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
