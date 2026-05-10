# scenario-simulator

재난 대피 시나리오 및 주민 부하 테스트용 서비스.

## DB Schema 소유권

**scenario-simulator는 DB schema owner가 아니다.**

- `spring.flyway.enabled=false` — 이 서비스는 자체 Flyway migration을 실행하지 않는다.
- `test_scenario_record` 테이블은 외부 migration job이 먼저 생성해야 한다.
- AWS dev 환경에서는 기존 db-migration Job에 별도 migration을 추가해야 한다.
  (실제 migration SQL은 `k8s-manifest`의 db-migration Job에서 별도 V6로 관리한다.)

### Schema 참조

`src/main/resources/db/migration/V1__create_test_scenario_record.sql` 파일은
schema 참조용으로만 보관되며, 애플리케이션에 의해 실행되지 않는다.

## 실행 방법

```bash
# local 프로파일 (H2 인메모리, Flyway 비활성)
./gradlew :services:scenario-simulator:bootRun
```
