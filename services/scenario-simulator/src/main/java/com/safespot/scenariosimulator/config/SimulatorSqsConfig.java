package com.safespot.scenariosimulator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Slf4j
@Configuration
public class SimulatorSqsConfig {

    @Value("${simulator.sqs.region:ap-northeast-2}")
    private String region;

    @Value("${simulator.sqs.endpoint-override:}")
    private String endpointOverride;

    @Bean
    @ConditionalOnProperty(name = "simulator.sqs.enabled", havingValue = "true")
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (!endpointOverride.isBlank()) {
            log.info("[SIM-SQS] using endpoint override: {}", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }

        return builder.build();
    }
}
