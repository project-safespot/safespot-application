package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * MVP temporary fallback: appends failed event envelopes to a local NDJSON file
 * for manual replay. This is NOT equivalent to a transactional outbox.
 *
 * Limitations:
 * - Not crash-safe: in-flight records can be lost on JVM crash before file write.
 * - Single-instance only: not replicated across nodes.
 * - Manual replay only: requires EventPublishReplayService or operator action.
 * Target architecture: replace with lightweight transactional outbox table.
 *
 * Path is configurable via safespot.sqs.publish-failure-file.
 * If the path is not writable, falls back to log-only mode without throwing.
 */
@Slf4j
@Component
public class EventPublishFailureWriter {

    private final ObjectMapper objectMapper;
    private final ApiCoreMetrics metrics;
    private final Path failurePath;

    public EventPublishFailureWriter(
            ObjectMapper objectMapper,
            ApiCoreMetrics metrics,
            @Value("${safespot.sqs.publish-failure-file:/var/log/safespot/event-publish-failures.ndjson}")
            String fallbackFilePath) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.failurePath = Paths.get(fallbackFilePath);
    }

    public void write(EventPublishFailureRecord record) {
        try {
            String line = objectMapper.writeValueAsString(record) + "\n";
            Path parent = failurePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(failurePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[SQS-FALLBACK] failure record persisted: eventId={} eventType={} file={}",
                    record.getEventId(), record.getEventType(), failurePath);
            metrics.incSqsPublishFallbackPersist("success");
        } catch (Exception e) {
            // File write failure must never propagate to caller.
            log.error("[SQS-FALLBACK] failed to persist failure record: eventId={} eventType={} file={} error={}",
                    record.getEventId(), record.getEventType(), failurePath, e.getMessage());
            metrics.incSqsPublishFallbackPersist("failure");
        }
    }
}
