package com.safespot.asyncworker.context;

import com.safespot.asyncworker.CacheWorkerApplication;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

@SpringBootTest(classes = CacheWorkerApplication.class)
@ActiveProfiles("cache-worker")
class CacheWorkerContextTest {

    @MockitoBean
    StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    DataSource dataSource;

    @Autowired
    ApplicationContext ctx;

    @Test
    void contextLoads() {
    }

    @Test
    void cache_worker_전용_빈_등록됨() {
        assertThat(ctx.getBean(EnvironmentCacheService.class)).isNotNull();
    }

    @Test
    void readmodel_worker_전용_빈_미등록() {
        assertThatThrownBy(() -> ctx.getBean(DisasterReadModelService.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
