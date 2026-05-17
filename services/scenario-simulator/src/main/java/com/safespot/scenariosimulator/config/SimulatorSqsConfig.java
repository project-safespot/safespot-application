package com.safespot.scenariosimulator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Slf4j
@Configuration
@EnableConfigurationProperties(SimulatorSqsProperties.class)
public class SimulatorSqsConfig {

    @Bean
    @ConditionalOnProperty(name = "simulator.sqs.enabled", havingValue = "true")
    public SqsClient sqsClient(SimulatorSqsProperties properties) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (!properties.getEndpointOverride().isBlank()) {
            log.info("[SIM-SQS] using endpoint override: {}", properties.getEndpointOverride());
            builder.endpointOverride(URI.create(properties.getEndpointOverride()));
        }

        return builder.build();
    }
}
