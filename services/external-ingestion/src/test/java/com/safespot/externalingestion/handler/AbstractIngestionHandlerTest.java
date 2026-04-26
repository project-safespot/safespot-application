package com.safespot.externalingestion.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.client.ExternalApiClient;
import com.safespot.externalingestion.client.ExternalApiException;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.domain.enums.ExecutionStatus;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractIngestionHandlerTest {

    @Mock private ExternalApiSourceRepository sourceRepo;
    @Mock private ExternalApiExecutionLogRepository executionLogRepo;
    @Mock private ExternalApiRawPayloadRepository rawPayloadRepo;
    @Mock private NormalizationQueue normalizationQueue;
    @Mock private ExternalApiClient externalApiClient;
    @Mock private PlatformTransactionManager txManager;

    private TestHandler handler;

    @BeforeEach
    void setUp() {
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        handler = new TestHandler();
        ReflectionTestUtils.setField(handler, "sourceRepo", sourceRepo);
        ReflectionTestUtils.setField(handler, "executionLogRepo", executionLogRepo);
        ReflectionTestUtils.setField(handler, "rawPayloadRepo", rawPayloadRepo);
        ReflectionTestUtils.setField(handler, "normalizationQueue", normalizationQueue);
        ReflectionTestUtils.setField(handler, "externalApiClient", externalApiClient);
        ReflectionTestUtils.setField(handler, "metrics", metrics);
        ReflectionTestUtils.setField(handler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(handler, "transactionTemplate", txTemplate);
    }

    @Test
    void execute_success_publishesNormalizationMessage() throws Exception {
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceId(1L);
        source.setSourceCode("TEST_SOURCE");
        source.setBaseUrl("https://test.api");
        source.setActive(true);

        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setExecutionId(1L);

        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);

        given(sourceRepo.findBySourceCode("TEST_SOURCE")).willReturn(Optional.of(source));
        given(executionLogRepo.save(any())).willReturn(execLog);
        given(externalApiClient.get(anyString(), any())).willReturn("{\"items\":[{\"a\":1}]}");
        given(rawPayloadRepo.existsByPayloadHash(anyString())).willReturn(false);
        given(rawPayloadRepo.save(any())).willReturn(raw);

        IngestionResult result = handler.execute();

        assertThat(result.isSuccess()).isTrue();
        verify(normalizationQueue).publish(any(NormalizationMessage.class));
        verify(executionLogRepo, times(2)).save(any()); // RUNNING + SUCCESS
    }

    @Test
    void execute_disabledSource_returnsSkipped() {
        handler = new TestHandler(false);
        ReflectionTestUtils.setField(handler, "sourceRepo", sourceRepo);
        ReflectionTestUtils.setField(handler, "executionLogRepo", executionLogRepo);
        ReflectionTestUtils.setField(handler, "rawPayloadRepo", rawPayloadRepo);
        ReflectionTestUtils.setField(handler, "normalizationQueue", normalizationQueue);
        ReflectionTestUtils.setField(handler, "externalApiClient", externalApiClient);
        ReflectionTestUtils.setField(handler, "metrics", new IngestionMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(handler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(handler, "transactionTemplate", new TransactionTemplate(txManager));

        IngestionResult result = handler.execute();

        assertThat(result.getStatus()).isEqualTo(IngestionResult.Status.SKIPPED);
        verifyNoInteractions(sourceRepo, externalApiClient);
    }

    @Test
    void execute_duplicateHash_returnsDuplicate() throws Exception {
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceId(1L);
        source.setSourceCode("TEST_SOURCE");
        source.setBaseUrl("https://test.api");
        source.setActive(true);

        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setExecutionId(1L);

        given(sourceRepo.findBySourceCode("TEST_SOURCE")).willReturn(Optional.of(source));
        given(executionLogRepo.save(any())).willReturn(execLog);
        given(externalApiClient.get(anyString(), any())).willReturn("{\"data\":\"same\"}");
        given(rawPayloadRepo.existsByPayloadHash(anyString())).willReturn(true);

        IngestionResult result = handler.execute();

        assertThat(result.getStatus()).isEqualTo(IngestionResult.Status.DUPLICATE);
        verify(normalizationQueue, never()).publish(any());
    }

    @Test
    void execute_apiClientThrows_returnsFailed() throws Exception {
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceId(1L);
        source.setSourceCode("TEST_SOURCE");
        source.setBaseUrl("https://test.api");
        source.setActive(true);

        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setExecutionId(1L);

        given(sourceRepo.findBySourceCode("TEST_SOURCE")).willReturn(Optional.of(source));
        given(executionLogRepo.save(any())).willReturn(execLog);
        given(externalApiClient.get(anyString(), any()))
            .willThrow(new ExternalApiException("server error", ExternalApiException.ErrorType.SERVER_ERROR, 500));

        IngestionResult result = handler.execute();

        assertThat(result.getStatus()).isEqualTo(IngestionResult.Status.FAILED);
        verify(normalizationQueue, never()).publish(any());
    }

    @Test
    void execute_seoulPathKey_isRedactedFromAllFailurePaths() throws Exception {
        String dummyKey = "TEST_SEOUL_KEY";
        String template = "http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20/";

        TestSeoulHandler seoulHandler = new TestSeoulHandler(dummyKey);
        ReflectionTestUtils.setField(seoulHandler, "sourceRepo", sourceRepo);
        ReflectionTestUtils.setField(seoulHandler, "executionLogRepo", executionLogRepo);
        ReflectionTestUtils.setField(seoulHandler, "rawPayloadRepo", rawPayloadRepo);
        ReflectionTestUtils.setField(seoulHandler, "normalizationQueue", normalizationQueue);
        ReflectionTestUtils.setField(seoulHandler, "externalApiClient", externalApiClient);
        ReflectionTestUtils.setField(seoulHandler, "metrics", new IngestionMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(seoulHandler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(seoulHandler, "transactionTemplate", new TransactionTemplate(txManager));

        ExternalApiSource source = new ExternalApiSource();
        source.setSourceId(1L);
        source.setSourceCode("SEOUL_TEST");
        source.setBaseUrl(template);
        source.setActive(true);

        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setExecutionId(1L);

        given(sourceRepo.findBySourceCode("SEOUL_TEST")).willReturn(Optional.of(source));
        given(executionLogRepo.save(any())).willReturn(execLog);

        String urlWithKey = template.replace("{KEY}", dummyKey);
        given(externalApiClient.get(anyString(), any()))
            .willThrow(new ExternalApiException(
                "4xx from " + urlWithKey, ExternalApiException.ErrorType.CLIENT_ERROR, 400));

        Logger logger = (Logger) LoggerFactory.getLogger(AbstractIngestionHandler.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        IngestionResult result;
        try {
            result = seoulHandler.execute();
        } finally {
            logger.detachAppender(listAppender);
        }

        // IngestionResult.message must not contain the key
        assertThat(result.getMessage()).doesNotContain(dummyKey);

        // ExternalApiExecutionLog.errorMessage must not contain the key
        ArgumentCaptor<ExternalApiExecutionLog> captor = ArgumentCaptor.forClass(ExternalApiExecutionLog.class);
        verify(executionLogRepo, atLeastOnce()).save(captor.capture());
        captor.getAllValues().stream()
            .filter(l -> l.getErrorMessage() != null)
            .forEach(l -> assertThat(l.getErrorMessage()).doesNotContain(dummyKey));

        // Formatted log messages must not contain the key
        List<String> formatted = listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        formatted.forEach(msg -> assertThat(msg).doesNotContain(dummyKey));
    }

    /** 테스트 전용 핸들러 stub */
    static class TestHandler extends AbstractIngestionHandler {
        private final boolean enabled;

        TestHandler() { this.enabled = true; }
        TestHandler(boolean enabled) { this.enabled = enabled; }

        @Override public String getSourceCode() { return "TEST_SOURCE"; }
        @Override public boolean isEnabled() { return enabled; }
        @Override protected Map<String, String> buildRequestParams() { return Map.of("key", "val"); }
        @Override protected int countItems(String body) { return 1; }
    }

    static class TestSeoulHandler extends AbstractIngestionHandler {
        private final String apiKey;

        TestSeoulHandler(String apiKey) { this.apiKey = apiKey; }

        @Override public String getSourceCode() { return "SEOUL_TEST"; }
        @Override public String getProviderApiKey() { return apiKey; }
        @Override protected String buildFinalUrl(String sourceUrl) { return sourceUrl.replace("{KEY}", apiKey); }
        @Override protected Map<String, String> buildRequestParams() { return Map.of(); }
        @Override protected int countItems(String body) { return 0; }
    }
}
