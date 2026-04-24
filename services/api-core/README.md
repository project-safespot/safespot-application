# api-core

관리자 보호 워크로드 및 인증/쓰기 API를 담당하는 서비스.

- DB commit 이후 durable event publication을 담당한다.
- stale cache는 필요한 경우 `DEL`로 무효화한다.
- Redis read model `SET`/재생성은 담당하지 않는다. 재생성은 `async-worker` 책임이다.
- 공개 조회와 RDS fallback 처리는 `api-public-read` 책임이다.
