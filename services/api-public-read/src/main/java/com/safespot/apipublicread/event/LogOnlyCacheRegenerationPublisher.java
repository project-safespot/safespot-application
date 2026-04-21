package com.safespot.apipublicread.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogOnlyCacheRegenerationPublisher implements CacheRegenerationPublisher {

    @Override
    public void publish(String redisKey) {
        log.info("[CacheRegen] regeneration requested for key={}", redisKey);
        // TODO: SQS 연동 시 실제 이벤트 발행으로 교체
    }
}
