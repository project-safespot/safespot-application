package com.safespot.apicore.cache;

import com.safespot.apicore.domain.enums.DisasterType;
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
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete("shelter:status:" + shelterId);
            redisTemplate.delete("admin:shelter:status:" + shelterId);
            log.debug("[CACHE] DEL shelter:status:{} admin:shelter:status:{}", shelterId, shelterId);
        } catch (Exception e) {
            log.warn("[CACHE] Redis DEL failed shelterId={} error={}", shelterId, e.getMessage());
        }
    }

    public void invalidateShelterListCache(String shelterType, DisasterType disasterType) {
        if (redisTemplate == null) return;
        if (shelterType == null || disasterType == null) return;
        String listKey = "shelter:list:" + shelterType + ":" + disasterType.name();
        try {
            redisTemplate.delete(listKey);
            log.debug("[CACHE] DEL {}", listKey);
        } catch (Exception e) {
            log.warn("[CACHE] Redis DEL failed key={} error={}", listKey, e.getMessage());
        }
    }
}
