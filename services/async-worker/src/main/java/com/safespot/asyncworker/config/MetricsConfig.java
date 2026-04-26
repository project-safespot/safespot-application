package com.safespot.asyncworker.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// async-worker metric 정책:
//   - Prometheus scrape 대상이 아니다. /actuator/prometheus endpoint를 사용하지 않는다.
//   - spring.main.web-application-type=none 이므로 HTTP 기반 metric export는 지원하지 않는다.
//   - 수집된 metric은 CloudWatch/SQS metric과 함께 사용하는 보조 metric이다.
//   - 운영 metric은 SQS visible/oldest message, Lambda duration/error 등 CloudWatch 기준으로 관측한다.
@Configuration
public class MetricsConfig {

    // micrometer-registry-prometheus + actuator가 classpath에 있으면
    // PrometheusMeterRegistry가 자동 구성된다 → 이 bean은 생성되지 않는다.
    // Prometheus 없이 구동되는 환경(테스트, 로컬)의 fallback 용도로만 동작한다.
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
