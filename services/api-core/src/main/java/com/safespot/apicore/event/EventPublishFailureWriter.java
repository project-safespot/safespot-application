package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublishFailureWriter {

    static final Path FAILURE_FILE = Paths.get("/var/log/safespot/event-publish-failures.ndjson");

    private final ObjectMapper objectMapper;
    private final ApiCoreMetrics metrics;

    public void write(EventPublishFailureRecord record) {
        try {
            String line = objectMapper.writeValueAsString(record) + "\n";
            ensureParentDir();
            Files.writeString(FAILURE_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[SQS-FALLBACK] failure record persisted: eventId={} file={}",
                    record.getEventId(), FAILURE_FILE);
            metrics.incSqsPublishFallbackPersist("success");
        } catch (Exception e) {
            // File write failure must never propagate to caller.
            log.error("[SQS-FALLBACK] failed to persist failure record: eventId={} error={}",
                    record.getEventId(), e.getMessage());
            metrics.incSqsPublishFallbackPersist("failure");
        }
    }

    private void ensureParentDir() throws IOException {
        Path parent = FAILURE_FILE.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
