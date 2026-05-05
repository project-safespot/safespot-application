package com.safespot.asyncworker.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
@ComponentScan(basePackages = {
    "com.safespot.asyncworker.consumer",
    "com.safespot.asyncworker.envelope",
    "com.safespot.asyncworker.handler",
    "com.safespot.asyncworker.idempotency",
    "com.safespot.asyncworker.metrics",
    "com.safespot.asyncworker.redis",
    "com.safespot.asyncworker.repository",
    "com.safespot.asyncworker.service"
})
@Import({CacheWorkerConfig.class, ReadModelWorkerConfig.class, AsyncWorkerConfig.class})
public class LambdaConfig {

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s",
            requireEnv("DB_HOST"), requireEnv("DB_PORT"), requireEnv("DB_NAME")));
        config.setUsername(requireEnv("DB_USER"));
        config.setPassword(requireEnv("DB_PASSWORD"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(
            requireEnv("REDIS_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))
        );
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(2000))
            .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        factory.setEagerInitialization(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.create();
    }

    @Bean
    public MeterRegistry meterRegistry() {
        String namespace = System.getenv("METRICS_NAMESPACE");
        if (namespace != null && !namespace.isBlank()) {
            CloudWatchConfig config = new CloudWatchConfig() {
                @Override public String get(String key) { return null; }
                @Override public String namespace() { return namespace; }
            };
            return new CloudWatchMeterRegistry(config, Clock.SYSTEM, CloudWatchAsyncClient.create());
        }
        return new SimpleMeterRegistry();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required env var not set: " + name);
        }
        return value;
    }
}
