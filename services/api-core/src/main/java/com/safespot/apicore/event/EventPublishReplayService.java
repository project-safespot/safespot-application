package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MVP replay helper for manually recovering events from the NDJSON failure file.
 *
 * Invocation: call replayAll() via Spring Boot Actuator endpoint, admin API,
 * or a future CLI runner. No automated scheduling — operator-triggered only.
 *
 * This does not guarantee exactly-once delivery. Consumer-side idempotency
 * (async-worker) remains the dedup authority.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublishReplayService {

    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SqsClient sqsClient;

    @Value("${safespot.sqs.queue-url:}")
    private String queueUrl;

    public void replayAll(Path sourceFile) {
        if (sqsClient == null || queueUrl.isBlank()) {
            log.warn("[REPLAY] SQS not configured — replay aborted");
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile);
        } catch (Exception e) {
            log.error("[REPLAY] cannot read failure file: path={} error={}", sourceFile, e.getMessage());
            return;
        }

        int success = 0, failure = 0, skipped = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) { skipped++; continue; }

            String envelope = extractReplayableEnvelope(line);
            if (envelope == null) {
                log.warn("[REPLAY] malformed line skipped: lineNumber={}", i + 1);
                skipped++;
                continue;
            }

            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(envelope)
                        .build());
                log.info("[REPLAY] replayed: lineNumber={}", i + 1);
                success++;
            } catch (Exception e) {
                log.error("[REPLAY] publish failed: lineNumber={} error={}", i + 1, e.getMessage());
                failure++;
            }
        }

        log.info("[REPLAY] complete: success={} failure={} skipped={} total={}",
                success, failure, skipped, lines.size());
    }

    private String extractReplayableEnvelope(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            JsonNode field = node.get("replayableEnvelope");
            if (field == null || field.isNull()) return null;
            return field.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
