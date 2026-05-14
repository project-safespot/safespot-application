package com.safespot.apipublicread.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// local/test profile 전용. dev/prod에서는 SqsDisasterWarmupPublisher가 사용되어야 한다.
// safespot.warmup.enabled=true인데 이 bean이 선택되면 실제 SQS 발행이 일어나지 않는다.
// dev에서 SQS publisher가 없으면 bean 주입 실패로 앱 시작이 실패하여 명확히 구분된다.
@Slf4j
@Component
@Profile({"local", "test"})
@ConditionalOnProperty(
        name = "safespot.cache-regeneration.publisher-mode",
        havingValue = "log",
        matchIfMissing = true
)
public class LogOnlyDisasterWarmupPublisher implements DisasterWarmupPublisher {

    @Override
    public void publish(int limit, boolean includeDetails) {
        DisasterWarmupEnvelope envelope = DisasterWarmupEnvelope.build(limit, includeDetails);
        log.info("[DisasterWarmup][log-only] eventType={} eventId={} limit={} includeDetails={} traceId={} idempotencyKey={}",
                envelope.eventType(), envelope.eventId(), limit, includeDetails,
                envelope.traceId(), envelope.idempotencyKey());
    }
}
