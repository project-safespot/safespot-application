package com.safespot.apipublicread.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.event.CacheKeyFamilyResolver;
import com.safespot.apipublicread.event.CacheRegenerationEnvelopeFactory;
import com.safespot.apipublicread.event.CacheRegenerationPublishFailureRecorder;
import com.safespot.apipublicread.event.CacheRegenerationRouteResolver;
import com.safespot.apipublicread.event.SqsCacheRegenerationPublisher;
import com.safespot.apipublicread.event.SqsDisasterWarmupPublisher;
import com.safespot.apipublicread.event.SqsQueueUrlProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "safespot.cache-regeneration.publisher-mode", havingValue = "sqs")
public class SqsPublisherConfig {

    @Value("${safespot.aws.sqs.endpoint:}")
    private String sqsEndpoint;

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder().region(Region.AP_NORTHEAST_2);
        if (!sqsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sqsEndpoint));
        }
        return builder.build();
    }

    @Bean
    public SqsQueueUrlProvider sqsQueueUrlProvider(
            @Value("${safespot.aws.sqs.cache-refresh-queue-url}") String cacheRefreshQueueUrl,
            @Value("${safespot.aws.sqs.readmodel-refresh-queue-url}") String readModelRefreshQueueUrl,
            @Value("${safespot.aws.sqs.environment-cache-refresh-queue-url}") String environmentCacheRefreshQueueUrl) {
        Assert.hasText(cacheRefreshQueueUrl,
                "safespot.aws.sqs.cache-refresh-queue-url must not be empty");
        Assert.hasText(readModelRefreshQueueUrl,
                "safespot.aws.sqs.readmodel-refresh-queue-url must not be empty");
        Assert.hasText(environmentCacheRefreshQueueUrl,
                "safespot.aws.sqs.environment-cache-refresh-queue-url must not be empty");
        return new SqsQueueUrlProvider(cacheRefreshQueueUrl, readModelRefreshQueueUrl, environmentCacheRefreshQueueUrl);
    }

    @Bean
    public CacheRegenerationPublishFailureRecorder cacheRegenerationPublishFailureRecorder(
            @Value("${safespot.cache-regeneration.publish-failure-file:/tmp/cache-regeneration-publish-failures.jsonl}")
            String failureFilePath) {
        return new CacheRegenerationPublishFailureRecorder(failureFilePath);
    }

    @Bean
    public SqsCacheRegenerationPublisher sqsCacheRegenerationPublisher(
            SqsClient sqsClient,
            SqsQueueUrlProvider queueUrlProvider,
            ObjectMapper objectMapper,
            CacheKeyFamilyResolver familyResolver,
            CacheRegenerationRouteResolver routeResolver,
            CacheRegenerationEnvelopeFactory envelopeFactory,
            CacheRegenerationPublishFailureRecorder failureRecorder,
            MeterRegistry meterRegistry) {
        return new SqsCacheRegenerationPublisher(sqsClient, queueUrlProvider, objectMapper,
                familyResolver, routeResolver, envelopeFactory, failureRecorder, meterRegistry);
    }

    @Bean
    public SqsDisasterWarmupPublisher sqsDisasterWarmupPublisher(
            SqsClient sqsClient,
            SqsQueueUrlProvider queueUrlProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        return new SqsDisasterWarmupPublisher(sqsClient, queueUrlProvider, objectMapper, meterRegistry);
    }
}
