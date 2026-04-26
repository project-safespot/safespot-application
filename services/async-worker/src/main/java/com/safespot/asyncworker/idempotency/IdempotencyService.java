package com.safespot.asyncworker.idempotency;

import java.time.Duration;

public interface IdempotencyService {

    /**
     * SETNX "PROCESSING" 기반 원자적 획득.
     * true  = PROCESSING 세팅 성공 또는 기존 PROCESSING 상태(이전 실패, 재시도 허용) → 비즈니스 로직 진행
     * false = COMPLETED 상태 (이미 성공 처리됨) → no-op
     */
    boolean tryAcquire(String idempotencyKey, Duration ttl);

    /**
     * 처리 성공 후 키를 COMPLETED 상태로 전환한다.
     * 실패해도 예외를 전파하지 않는다 — 키가 PROCESSING으로 남으면 다음 tryAcquire가 true를 반환해 재처리된다.
     */
    void markCompleted(String idempotencyKey, Duration ttl);

    /**
     * 처리 실패 시 획득한 키를 삭제한다 (PROCESSING → absent).
     * 실패해도 예외를 전파하지 않는다 — 키가 PROCESSING으로 남아도 다음 tryAcquire가 true를 반환해 재처리된다.
     */
    void release(String idempotencyKey);
}
