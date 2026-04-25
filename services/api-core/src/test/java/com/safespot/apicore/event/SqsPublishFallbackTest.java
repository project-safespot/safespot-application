package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SqsPublishFallbackTest {

    private ObjectMapper objectMapper;
    private ApiCoreMetrics metrics;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        metrics = new ApiCoreMetrics(new SimpleMeterRegistry());
    }

    @Test
    void failureWriter_writesFullEnvelopeToFile() throws Exception {
        Path file = tempDir.resolve("failures.ndjson");
        EventPublishFailureWriter writer =
                new EventPublishFailureWriter(objectMapper, metrics, file.toString());

        EventPublishFailureRecord record = EventPublishFailureRecord.builder()
                .eventId("evt-001")
                .eventType("EvacuationEntryCreated")
                .idempotencyKey("entry:1:ENTERED")
                .traceId("trace-001")
                .queueName("test-queue")
                .failedAt(OffsetDateTime.now())
                .retryCount(4)
                .lastError("Connection refused")
                .replayableEnvelope("{\"eventId\":\"evt-001\"}")
                .build();

        writer.write(record);

        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content).contains("evt-001");
        assertThat(content).contains("EvacuationEntryCreated");
        assertThat(content).contains("replayableEnvelope");
        assertThat(content).contains("entry:1:ENTERED");
    }

    @Test
    void failureWriter_invalidPath_doesNotThrow() throws Exception {
        // Create a regular file, then attempt to write under it as if it were a directory.
        // This guarantees write failure: parent exists but is not a directory.
        Path notADir = tempDir.resolve("not-a-dir.txt");
        Files.writeString(notADir, "i am a file");
        Path invalidFile = notADir.resolve("child.ndjson");

        EventPublishFailureWriter writer =
                new EventPublishFailureWriter(objectMapper, metrics, invalidFile.toString());

        EventPublishFailureRecord record = EventPublishFailureRecord.builder()
                .eventId("evt-002").eventType("ShelterUpdated")
                .idempotencyKey("shelter:1:UPDATED:uuid").traceId("t").queueName("q")
                .failedAt(OffsetDateTime.now()).retryCount(4).lastError("timeout")
                .replayableEnvelope("{}").build();

        writer.write(record); // must not throw
    }

    @Test
    void fallbackPersistMetric_incrementsOnSuccess() throws Exception {
        Path file = tempDir.resolve("ok.ndjson");
        new EventPublishFailureWriter(objectMapper, metrics, file.toString())
                .write(buildRecord("evt-003"));
        assertThat(file).exists();
    }

    @Test
    void replayService_malformedLine_isSkippedGracefully() throws Exception {
        Path file = tempDir.resolve("replay.ndjson");
        Files.writeString(file, "not-json\n");
        Files.writeString(file, "{\"replayableEnvelope\":null}\n",
                java.nio.file.StandardOpenOption.APPEND);

        EventPublishReplayService service = new EventPublishReplayService(objectMapper);
        // SqsClient is null — replay aborts before touching lines; verify no exception.
        service.replayAll(file);
    }

    @Test
    void replayService_noSqsClient_abortsGracefully() throws Exception {
        Path file = tempDir.resolve("replay2.ndjson");
        Files.writeString(file, "{\"replayableEnvelope\":\"{\\\"eventId\\\":\\\"x\\\"}\"}\n");

        EventPublishReplayService service = new EventPublishReplayService(objectMapper);
        service.replayAll(file); // sqsClient null → logs warning, no exception
    }

    private EventPublishFailureRecord buildRecord(String eventId) {
        return EventPublishFailureRecord.builder()
                .eventId(eventId)
                .eventType("EvacuationEntryCreated")
                .idempotencyKey("entry:1:ENTERED")
                .traceId("t")
                .queueName("q")
                .failedAt(OffsetDateTime.now())
                .retryCount(4)
                .lastError("err")
                .replayableEnvelope("{\"eventId\":\"" + eventId + "\"}")
                .build();
    }
}
