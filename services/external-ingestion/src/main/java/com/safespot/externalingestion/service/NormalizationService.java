package com.safespot.externalingestion.service;

import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiNormalizationError;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.normalizer.Normalizer;
import com.safespot.externalingestion.normalizer.NormalizationResult;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.repository.ExternalApiExecutionLogRepository;
import com.safespot.externalingestion.repository.ExternalApiNormalizationErrorRepository;
import com.safespot.externalingestion.repository.ExternalApiRawPayloadRepository;
import com.safespot.externalingestion.repository.ExternalApiSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NormalizationService {

    private final Map<String, Normalizer> normalizerMap;
    private final ExternalApiRawPayloadRepository rawPayloadRepo;
    private final ExternalApiExecutionLogRepository executionLogRepo;
    private final ExternalApiNormalizationErrorRepository normErrRepo;
    private final ExternalApiSourceRepository sourceRepo;
    private final IngestionMetrics metrics;

    public NormalizationService(List<Normalizer> normalizers,
                                 ExternalApiRawPayloadRepository rawPayloadRepo,
                                 ExternalApiExecutionLogRepository executionLogRepo,
                                 ExternalApiNormalizationErrorRepository normErrRepo,
                                 ExternalApiSourceRepository sourceRepo,
                                 IngestionMetrics metrics) {
        this.normalizerMap = normalizers.stream()
            .collect(Collectors.toMap(Normalizer::getSourceCode, Function.identity()));
        this.rawPayloadRepo = rawPayloadRepo;
        this.executionLogRepo = executionLogRepo;
        this.normErrRepo = normErrRepo;
        this.sourceRepo = sourceRepo;
        this.metrics = metrics;
        log.info("NormalizationService registered normalizers: {}", this.normalizerMap.keySet());
    }

    @Transactional
    public void process(NormalizationMessage msg) {
        String sourceCode = resolveSourceCode(msg.getSourceId());
        long start = System.currentTimeMillis();

        ExternalApiRawPayload raw = rawPayloadRepo.findById(msg.getRawId()).orElse(null);
        if (raw == null) {
            log.error("[Normalizer] raw_id={} not found, traceId={}", msg.getRawId(), msg.getTraceId());
            return;
        }

        Normalizer normalizer = normalizerMap.get(sourceCode);
        if (normalizer == null) {
            log.warn("[Normalizer] no normalizer for source={} raw_id={}", sourceCode, msg.getRawId());
            return;
        }

        try {
            NormalizationResult result = normalizer.normalize(raw);
            metrics.recordNormalizationDuration(sourceCode, System.currentTimeMillis() - start);

            updateExecutionLog(msg.getExecutionId(), result);

            if (result.hasFailures()) {
                saveNormalizationErrors(raw, sourceCode, result.getErrors());
            }

            log.info("[Normalizer] source={} traceId={} succeeded={} failed={}",
                sourceCode, msg.getTraceId(), result.getSucceeded(), result.getFailed());
        } catch (Exception e) {
            metrics.incrementNormalizationFailure(sourceCode, "db_error");
            log.error("[Normalizer] source={} traceId={} unexpected error", sourceCode, msg.getTraceId(), e);
            saveNormalizationError(raw, sourceCode, e.getMessage());
        }
    }

    private String resolveSourceCode(Long sourceId) {
        return sourceRepo.findById(sourceId)
            .map(ExternalApiSource::getSourceCode)
            .orElse("UNKNOWN");
    }

    private void updateExecutionLog(Long executionId, NormalizationResult result) {
        executionLogRepo.findById(executionId).ifPresent(log -> {
            log.setRecordsNormalized(log.getRecordsNormalized() + result.getSucceeded());
            log.setRecordsFailed(log.getRecordsFailed() + result.getFailed());
            log.setEndedAt(OffsetDateTime.now());
            executionLogRepo.save(log);
        });
    }

    private void saveNormalizationErrors(ExternalApiRawPayload raw, String sourceCode, List<String> errors) {
        for (String err : errors) {
            saveNormalizationError(raw, sourceCode, err);
        }
    }

    private void saveNormalizationError(ExternalApiRawPayload raw, String sourceCode, String reason) {
        try {
            ExternalApiNormalizationError error = new ExternalApiNormalizationError();
            error.setExecutionLog(raw.getExecutionLog());
            error.setRawPayload(raw);
            error.setSource(raw.getSource());
            error.setTargetTable(resolveTargetTable(sourceCode));
            error.setErrorReason(reason != null ? reason : "unknown");
            normErrRepo.save(error);
        } catch (Exception ex) {
            log.error("[Normalizer] failed to save normalization error source={}", sourceCode, ex);
        }
    }

    private String resolveTargetTable(String sourceCode) {
        return switch (sourceCode) {
            case "KMA_WEATHER" -> "weather_log";
            case "AIR_KOREA_AIR_QUALITY" -> "air_quality_log";
            case "SEOUL_SHELTER_EARTHQUAKE",
                 "SEOUL_SHELTER_LANDSLIDE",
                 "SEOUL_SHELTER_FLOOD" -> "shelter";
            default -> "disaster_alert";
        };
    }
}
