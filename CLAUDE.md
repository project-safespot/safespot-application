# safespot-application

## 프로젝트 구조

```
safespot-application/   ← 기준 repo (main branch)
services/
  api-core/
  api-public-read/
  async-worker/
  external-ingestion/
packages/
  common-redis-keys/
  event-schema/
```

## Worktree 구성 (방식 B)

| 경로 | Branch | 담당 서비스 |
|------|--------|-------------|
| `safespot-worktrees/api-core` | `worktree/api-core` | services/api-core |
| `safespot-worktrees/api-public-read` | `worktree/api-public-read` | services/api-public-read |
| `safespot-worktrees/external-ingestion` | `worktree/external-ingestion` | services/external-ingestion |
| `safespot-worktrees/review` | `review/codex-cross-review` | 교차 리뷰 전용 |

모든 `worktree/*` branch는 `origin/main` 기준의 독립 브랜치다.

## Git Workflow

- `main`은 PR + CI + 리뷰 승인 후에만 병합
- 각 worktree는 담당 서비스 범위 내에서만 수정
- 리뷰어 worktree(`review/`)는 직접 구현하지 않고 diff 검토와 수정 요청만 수행

자세한 규칙은 `docs/git-workflow.md` 참고.

## Worktree 스크립트

```bash
# 초기화
./deploy/local/worktree-init.sh

# 정리 (worktree만)
./deploy/local/worktree-teardown.sh

# 정리 + 로컬 branch 삭제
./deploy/local/worktree-teardown.sh --delete-branches
```

## Source of Truth

- API spec: /docs/api/
- RDS schema: /docs/data/
- ingestion spec: /docs/ingestion/

All implementations MUST follow these documents.
If conflict occurs, spec must be updated first.