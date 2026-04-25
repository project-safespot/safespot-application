package com.safespot.externalingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "ingestion.sqs.enabled", havingValue = "true")
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(
        @Value("${ingestion.sqs.region:ap-northeast-2}") String region,
        @Value("${ingestion.sqs.endpoint-override:}") String endpointOverride
    ) {
        SqsClientBuilder builder = SqsClient.builder()
            .region(Region.of(region));
        if (StringUtils.hasText(endpointOverride)) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }
}
