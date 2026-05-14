package com.safespot.apipublicread.dto;

// disaster read model은 region 차원 없이 서울 전용 global warmup만 지원한다.
// MVP 범위가 서울 단일 region이므로 region field를 request에 포함하지 않는다.
public record DisasterCacheWarmupRequest(int limit, boolean includeDetails) {}
