# Git Workflow

## 1. 기본 원칙

- `main`은 항상 배포 가능한 상태만 유지한다.
- 모든 작업은 `main`에서 직접 수행하지 않는다.
- 작업은 **서비스 단위 branch**에서 수행한다.
- 작성(Writer)과 리뷰(Reviewer)는 역할을 분리한다.

---

## 2. 브랜치 구조

### 2.1 고정 브랜치

- `main`
- `review/codex-cross-review-m`
- `review/codex-cross-review-s`

---

### 2.2 서비스 기준 작업 브랜치

각 서비스는 독립적인 branch namespace를 가진다.

| 서비스 | base branch |
|--------|------------|
| api-core | `worktree/api-core` |
| api-public-read | `worktree/api-public-read` |
| external-ingestion | `worktree/external-ingestion` |
| async-worker | `worktree/async-worker` |

---

## 3. 역할 매핑

### Writer (구현 담당)

- Codex / Claude 모두 동일 규칙 사용
- 특정 writer 전용 branch 없음
- **서비스 branch에서 직접 작업**

---

### Reviewer (리뷰 담당)

- `review/codex-cross-review-m`
- `review/codex-cross-review-s`

역할:

- diff 검토
- 설계 위반 체크
- 수정 요청

---

## 4. 브랜치 생성 규칙

### 4.1 네이밍 규칙

```
worktree/{service}-{feature}
```

### 예시

- `worktree/api-core-login`
- `worktree/api-public-read-shelter-nearby`
- `worktree/external-ingestion-poller`
- `worktree/async-worker-cache-refresh`

---

## 5. 작업 규칙

1. **서비스 경계 외 수정 금지**
2. 동일 파일을 여러 branch에서 동시에 수정 금지
3. PR 생성 전 반드시 최신 `main` 반영
4. 리뷰 없이 merge 금지
5. 작은 단위 PR 유지 (1 feature = 1 PR)

---

## 6. PR 흐름

```
feature branch
→ PR 생성 (base: main)
→ 반대 reviewer가 교차 검토
→ 수정 반영
→ CI 통과
→ main merge
```

---

## 7. Worktree 기반 작업 흐름

각 서비스는 독립 worktree에서 작업한다.

### 예시

```
safespot-worktrees/api-core
→ worktree/api-core-login

safespot-worktrees/external-ingestion
→ worktree/external-ingestion-poller
```

---

## 8. 리뷰 규칙

Reviewer는:

- 직접 구현 최소화
- diff 기반 피드백 우선
- 아키텍처 위반 여부 확인

---

## 9. 금지사항

### 9.1 구조 위반

- 여러 서비스를 하나의 branch에서 동시에 수정 금지
- writer 전용 공통 branch 사용 금지

---

### 9.2 협업 위반

- main 직접 push 금지
- 리뷰 없이 merge 금지

---

## 10. 핵심 설계 원칙

- 서비스 단위 격리
- worktree 기반 병렬 작업
- writer / reviewer 역할 분리
- 작은 PR 유지