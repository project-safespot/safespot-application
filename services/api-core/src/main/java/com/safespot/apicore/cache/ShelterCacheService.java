package com.safespot.apicore.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ShelterCacheService {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public void invalidateShelterCache(Long shelterId) {
        if (redisTemplate == null) {
            return;
        }
        String userKey = "shelter:status:" + shelterId;
        String adminKey = "admin:shelter:status:" + shelterId;
        try {
            redisTemplate.delete(userKey);
            redisTemplate.delete(adminKey);
            log.debug("[CACHE] DEL keys={},{}", userKey, adminKey);
        } catch (Exception e) {
            log.warn("[CACHE] Redis DEL failed shelterId={} error={}", shelterId, e.getMessage());
        }
    }
}
