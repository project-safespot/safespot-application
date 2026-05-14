package com.safespot.asyncworker.context;

import com.safespot.asyncworker.config.LambdaConfig;
import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.handler.CacheRegenerationAsyncWorkerHandler;
import com.safespot.asyncworker.handler.cache.CacheRegenerationCacheWorkerHandler;
import com.safespot.asyncworker.handler.readmodel.CacheRegenerationReadModelWorkerHandler;
import com.safespot.asyncworker.handler.readmodel.DisasterReadModelWarmupRequestedHandler;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import software.amazon.awssdk.services.sqs.SqsClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LambdaConfig.class)
@ActiveProfiles("async-worker")
class AsyncWorkerContextTest {

    // LambdaConfig.dataSource()의 requireEnv("DB_HOST") 호출 차단
    @MockitoBean DataSource dataSource;
    // LambdaConfig.redisConnectionFactory()의 requireEnv("REDIS_HOST") 호출 차단
    @MockitoBean LettuceConnectionFactory redisConnectionFactory;
    @MockitoBean StringRedisTemplate stringRedisTemplate;
    // LambdaConfig.sqsClient()의 AWS SDK 자격증명 조회 차단
    @MockitoBean SqsClient sqsClient;

    @Autowired ApplicationContext ctx;

    @Test
    void contextLoads() {
    }

    @Test
    void EventDispatcher_등록됨() {
        assertThat(ctx.getBean(EventDispatcher.class)).isNotNull();
    }

    @Test
    void cache_worker_서비스_빈_등록됨() {
        // async-worker 프로필은 cache-worker 이벤트를 처리하므로 해당 서비스가 필요
        assertThat(ctx.getBean(EnvironmentCacheService.class)).isNotNull();
    }

    @Test
    void readmodel_worker_서비스_빈_등록됨() {
        // async-worker 프로필은 readmodel-worker 이벤트를 처리하므로 해당 서비스가 필요
        assertThat(ctx.getBean(DisasterReadModelService.class)).isNotNull();
    }

    @Test
    void CacheRegenerationAsyncWorkerHandler_등록됨() {
        // async-worker 전용 통합 handler가 등록되어야 한다
        assertThat(ctx.getBean(CacheRegenerationAsyncWorkerHandler.class)).isNotNull();
    }

    @Test
    void CacheRegenerationCacheWorkerHandler_미등록() {
        // cache-worker 전용 handler는 async-worker 프로필에서 비활성화
        // CacheRegenerationAsyncWorkerHandler가 shelter/env 키 처리를 대신한다
        assertThatThrownBy(() -> ctx.getBean(CacheRegenerationCacheWorkerHandler.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void CacheRegenerationReadModelWorkerHandler_미등록() {
        // readmodel-worker 전용 handler는 async-worker 프로필에서 비활성화
        // CacheRegenerationAsyncWorkerHandler가 disaster 키 처리를 대신한다
        assertThatThrownBy(() -> ctx.getBean(CacheRegenerationReadModelWorkerHandler.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void DisasterReadModelWarmupRequestedHandler_bean_등록됨() {
        assertThat(ctx.getBean(DisasterReadModelWarmupRequestedHandler.class)).isNotNull();
    }

    @Test
    void EventDispatcher_DisasterReadModelWarmupRequested_핸들러_등록됨() {
        EventDispatcher dispatcher = ctx.getBean(EventDispatcher.class);
        assertThat(dispatcher.getRegisteredEventTypes())
            .contains(EventType.DisasterReadModelWarmupRequested);
    }

    @Test
    void EventDispatcher_등록된_handler_목록_9개_정확히_일치() {
        EventDispatcher dispatcher = ctx.getBean(EventDispatcher.class);
        assertThat(dispatcher.getRegisteredEventTypes())
            .containsExactlyInAnyOrder(
                EventType.EvacuationEntryCreated,
                EventType.EvacuationEntryExited,
                EventType.EvacuationEntryUpdated,
                EventType.ShelterUpdated,
                EventType.EnvironmentDataCollected,
                EventType.DisasterDataCollected,
                EventType.CacheRegenerationRequested,
                EventType.ShelterStatusWarmupRequested,
                EventType.DisasterReadModelWarmupRequested
            );
    }
}
