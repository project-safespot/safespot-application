package com.safespot.asyncworker.context;

import com.safespot.asyncworker.config.LambdaConfig;
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
@ActiveProfiles("readmodel-worker")
class ReadModelWorkerContextTest {

    @MockitoBean DataSource dataSource;
    @MockitoBean LettuceConnectionFactory redisConnectionFactory;
    @MockitoBean StringRedisTemplate stringRedisTemplate;
    @MockitoBean SqsClient sqsClient;

    @Autowired ApplicationContext ctx;

    @Test
    void contextLoads() {
    }

    @Test
    void readmodel_worker_전용_빈_등록됨() {
        assertThat(ctx.getBean(DisasterReadModelService.class)).isNotNull();
    }

    @Test
    void cache_worker_전용_빈_미등록() {
        assertThatThrownBy(() -> ctx.getBean(EnvironmentCacheService.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
