package com.safespot.asyncworker.redis;

// disaster:latest:{disasterType}:{region} pointer 형태
// full object가 아닌 alertId만 저장한다.
// 상세 조회는 disaster:detail:{alertId}를 참조한다.
public record DisasterLatestCacheValue(Long alertId) {}
