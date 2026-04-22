package com.safespot.asyncworker.context;

import com.safespot.asyncworker.ReadModelWorkerApplication;
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

@SpringBootTest(classes = ReadModelWorkerApplication.class)
@ActiveProfiles("readmodel-worker")
class ReadModelWorkerContextTest {

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
    void readmodel_worker_전용_빈_등록됨() {
        assertThat(ctx.getBean(DisasterReadModelService.class)).isNotNull();
    }

    @Test
    void cache_worker_전용_빈_미등록() {
        assertThatThrownBy(() -> ctx.getBean(EnvironmentCacheService.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
