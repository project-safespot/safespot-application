# Git Workflow

## 기본 원칙
- `main`은 항상 리뷰와 CI를 통과한 안정 상태만 유지한다.
- 모든 작업은 `main`에서 직접 하지 않고 별도 branch에서 진행한다.
- 작성자 branch와 리뷰어 branch는 역할을 분리한다.

## 고정 브랜치
- `main`
- `feat/codex-writer-api-track`
- `feat/claude-writer-ingestion-track`
- `review/codex-cross-review`
- `review/claude-cross-review`

## 역할 매핑
- `feat/codex-writer-api-track`: api-core, api-public-read 작성
- `feat/claude-writer-ingestion-track`: external-ingestion, async-worker 작성
- `review/codex-cross-review`: Claude 작성 결과 리뷰
- `review/claude-cross-review`: Codex 작성 결과 리뷰

## 작업 규칙
1. 같은 파일을 두 작성자 branch에서 동시에 수정하지 않는다.
2. 리뷰어는 원칙적으로 직접 구현하지 않고 diff 검토와 수정 요청을 우선한다.
3. PR 생성 전 최신 `main`을 반영한다.
4. `main` 병합은 PR + CI + 리뷰 승인 후에만 진행한다.

## 브랜치 생성 규칙
기능 작업은 고정 작성자 branch 아래에서 다시 세분화한다.

예시:
- `feat/codex-writer-api-track-login`
- `feat/codex-writer-api-track-shelter-nearby`
- `feat/claude-writer-ingestion-track-poller`
- `feat/claude-writer-ingestion-track-normalizer`

## 권장 흐름
1. `main` 최신화
2. 작성자 branch에서 기능 구현
3. PR 생성
4. 반대편 리뷰어가 교차 검토
5. 수정 반영
6. `main` merge
