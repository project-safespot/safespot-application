package com.safespot.externalingestion.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.client.ExternalApiClient;
import com.safespot.externalingestion.client.ExternalApiException;
import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.domain.enums.ExecutionStatus;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.repository.ExternalApiExecutionLogRepository;
import com.safespot.externalingestion.repository.ExternalApiRawPayloadRepository;
import com.safespot.externalingestion.repository.ExternalApiSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractIngestionHandler implements IngestionHandler {

    @Autowired protected ExternalApiSourceRepository sourceRepo;
    @Autowired protected ExternalApiExecutionLogRepository executionLogRepo;
    @Autowired protected ExternalApiRawPayloadRepository rawPayloadRepo;
    @Autowired protected NormalizationQueue normalizationQueue;
    @Autowired protected ExternalApiClient externalApiClient;
    @Autowired protected IngestionMetrics metrics;
    @Autowired protected ObjectMapper objectMapper;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile java.time.LocalDate countDate = java.time.LocalDate.now();

    protected int getRateLimitPerDay() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    @Transactional
    public IngestionResult execute() {
        if (!isEnabled()) {
            metrics.incrementSkipped(getSourceCode(), "disabled");
            log.info("[{}] source disabled — skip", getSourceCode());
            return IngestionResult.skipped("source disabled");
        }

        resetDailyCountIfNeeded();
        if (dailyCallCount.get() >= getRateLimitPerDay()) {
            metrics.incrementRateLimitExceeded(getSourceCode());
            metrics.incrementSkipped(getSourceCode(), "rate_limit");
            log.warn("[{}] daily rate limit reached ({}) — skip", getSourceCode(), getRateLimitPerDay());
            return IngestionResult.skipped("rate limit");
        }

        ExternalApiSource source = sourceRepo.findBySourceCode(getSourceCode())
            .orElseThrow(() -> new IllegalStateException("source not found: " + getSourceCode()));

        String traceId = UUID.randomUUID().toString();
        ExternalApiExecutionLog execLog = createExecutionLog(source, traceId);

        long fetchStart = System.currentTimeMillis();
        try {
            metrics.incrementApiCall(getSourceCode());

            Map<String, String> params = buildRequestParams();
            String responseBody = callWithRetry(source.getBaseUrl(), params, traceId);
            dailyCallCount.incrementAndGet();

            long latency = System.currentTimeMillis() - fetchStart;
            metrics.recordApiLatency(getSourceCode(), latency);

            String payloadHash = sha256(responseBody);
            if (rawPayloadRepo.existsByPayloadHash(payloadHash)) {
                log.debug("[{}] duplicate payload hash={} — skip raw save", getSourceCode(), payloadHash);
                finishExecutionLog(execLog, ExecutionStatus.SUCCESS, 0, null, null, null);
                metrics.recordFetchDuration(getSourceCode(), System.currentTimeMillis() - fetchStart);
                return IngestionResult.duplicate();
            }

            ExternalApiRawPayload raw = saveRawPayload(source, execLog, source.getBaseUrl(), params, responseBody, payloadHash);
            int itemCount = countItems(responseBody);

            normalizationQueue.publish(NormalizationMessage.of(
                raw.getRawId(), source.getSourceId(), execLog.getExecutionId(), traceId));

            finishExecutionLog(execLog, ExecutionStatus.SUCCESS, itemCount, null, null, null);
            metrics.recordFetchDuration(getSourceCode(), System.currentTimeMillis() - fetchStart);
            log.info("[{}] traceId={} fetched={}", getSourceCode(), traceId, itemCount);
            return IngestionResult.success(itemCount);

        } catch (ExternalApiException e) {
            metrics.incrementApiFailure(getSourceCode(), e.getErrorType().name().toLowerCase());
            finishExecutionLog(execLog, ExecutionStatus.FAILED, 0, e.getErrorType().name(), e.getMessage(), e.getHttpStatus());
            log.error("[{}] traceId={} api error type={} msg={}", getSourceCode(), traceId, e.getErrorType(), e.getMessage());
            return IngestionResult.failed(e.getMessage());
        } catch (Exception e) {
            metrics.incrementSkipped(getSourceCode(), "error");
            finishExecutionLog(execLog, ExecutionStatus.FAILED, 0, "INTERNAL_ERROR", e.getMessage(), null);
            log.error("[{}] traceId={} internal error", getSourceCode(), traceId, e);
            return IngestionResult.failed(e.getMessage());
        }
    }

    private String callWithRetry(String url, Map<String, String> params, String traceId) throws ExternalApiException {
        int maxRetries = 3;
        ExternalApiException lastEx = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    metrics.incrementRetry(getSourceCode());
                    long backoff = (long) Math.pow(2, attempt) * 500L;
                    Thread.sleep(backoff);
                }
                return externalApiClient.get(url, params);
            } catch (ExternalApiException e) {
                lastEx = e;
                if (e.getErrorType() == ExternalApiException.ErrorType.CLIENT_ERROR) {
                    throw e; // no retry on 4xx
                }
                log.warn("[{}] traceId={} attempt={} error={}", getSourceCode(), traceId, attempt + 1, e.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ExternalApiException("interrupted", ExternalApiException.ErrorType.NETWORK, null, ie);
            }
        }
        throw lastEx;
    }

    protected abstract Map<String, String> buildRequestParams();

    protected abstract int countItems(String responseBody);

    private ExternalApiExecutionLog createExecutionLog(ExternalApiSource source, String traceId) {
        ExternalApiExecutionLog log = new ExternalApiExecutionLog();
        log.setSource(source);
        log.setExecutionStatus(ExecutionStatus.RUNNING);
        log.setStartedAt(OffsetDateTime.now());
        log.setTraceId(traceId);
        return executionLogRepo.save(log);
    }

    private void finishExecutionLog(ExternalApiExecutionLog execLog, ExecutionStatus status,
                                     int recordsFetched, String errorCode, String errorMessage, Integer httpStatus) {
        execLog.setExecutionStatus(status);
        execLog.setEndedAt(OffsetDateTime.now());
        execLog.setRecordsFetched(recordsFetched);
        execLog.setErrorCode(errorCode);
        execLog.setErrorMessage(errorMessage);
        execLog.setHttpStatus(httpStatus);
        executionLogRepo.save(execLog);
    }

    private ExternalApiRawPayload saveRawPayload(ExternalApiSource source, ExternalApiExecutionLog execLog,
                                                   String url, Map<String, String> params,
                                                   String responseBody, String payloadHash) {
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setSource(source);
        raw.setExecutionLog(execLog);
        raw.setRequestUrl(url);
        raw.setResponseBody(responseBody);
        raw.setPayloadHash(payloadHash);
        raw.setCollectedAt(OffsetDateTime.now());
        raw.setRetentionExpiresAt(OffsetDateTime.now().plusDays(90));
        try {
            raw.setRequestParamsJson(objectMapper.writeValueAsString(params));
        } catch (Exception ignored) {}
        return rawPayloadRepo.save(raw);
    }

    protected int countItemsInArray(String responseBody, String... path) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            for (String key : path) {
                node = node.path(key);
            }
            if (node.isArray()) return node.size();
            if (!node.isMissingNode()) return 1;
        } catch (Exception e) {
            log.warn("[{}] failed to count items", getSourceCode(), e);
        }
        return 0;
    }

    private void resetDailyCountIfNeeded() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(countDate)) {
            synchronized (this) {
                if (!today.equals(countDate)) {
                    dailyCallCount.set(0);
                    countDate = today;
                }
            }
        }
    }

    protected static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("sha256 failed", e);
        }
    }
}
