package com.safespot.asyncworker.idempotency;

import java.time.Duration;

public interface IdempotencyService {

    /**
     * SETNX 기반 원자적 획득.
     * true  = 신규 등록 성공 → 비즈니스 로직 진행
     * false = 이미 처리됨   → no-op
     */
    boolean tryAcquire(String idempotencyKey, Duration ttl);
}
