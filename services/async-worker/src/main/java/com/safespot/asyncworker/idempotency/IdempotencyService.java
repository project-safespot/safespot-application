package com.safespot.asyncworker.idempotency;

import java.time.Duration;

public interface IdempotencyService {

    /**
     * SETNX 기반 원자적 획득.
     * true  = 신규 등록 성공 → 비즈니스 로직 진행
     * false = 이미 처리됨   → no-op
     */
    boolean tryAcquire(String idempotencyKey, Duration ttl);

    /**
     * 처리 실패 시 획득한 키를 반환한다.
     * 재시도에서 동일 이벤트가 duplicate로 차단되지 않도록 보장한다.
     * 실패해도 예외를 전파하지 않는다 — 원래 실패 결과가 우선이다.
     */
    void release(String idempotencyKey);
}
