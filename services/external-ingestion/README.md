# external-ingestion

외부 API 수집 및 데이터 정규화 서비스.

- 재난 메시지 정규화 책임은 `external-ingestion`이 가진다.
- MVP in-scope 재난 유형은 `EARTHQUAKE`, `LANDSLIDE`, `FLOOD`만 허용한다.
- DB 저장 전 raw 값과 canonical 값을 함께 보존해야 한다.
- `external-ingestion`은 `messageCategory`, `level`/`levelRank`, `isInScope`, `normalizationReason`도 함께 산출한다.
- DB commit 이후 필요한 event/trigger를 발행할 수 있다.
- Redis read model 재생성, public-read 필터링, UI 렌더링은 `external-ingestion` 책임이 아니다.
