package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheRegenerationPublishFailureRecorderTest {

    @TempDir
    Path tempDir;

    private CacheRegenerationEnvelope stubEnvelope(String cacheKey) {
        CacheRegenerationEnvelope envelope = mock(CacheRegenerationEnvelope.class);
        CacheRegenerationPayload payload = mock(CacheRegenerationPayload.class);
        when(envelope.eventId()).thenReturn("evt-001");
        when(envelope.idempotencyKey()).thenReturn("cache-regen:abc:1000");
        when(envelope.payload()).thenReturn(payload);
        when(payload.cacheKey()).thenReturn(cacheKey);
        lenient().when(payload.cacheKeyFamily()).thenReturn("disaster_messages_list");
        return envelope;
    }

    @Test
    void record_writesEnvelopeJsonAsJsonlLine() throws IOException {
        Path file = tempDir.resolve("failures.jsonl");
        CacheRegenerationPublishFailureRecorder recorder =
                new CacheRegenerationPublishFailureRecorder(file.toString());

        String json = "{\"eventType\":\"CacheRegenerationRequested\",\"cacheKey\":\"disaster:messages:list:seoul\"}";
        recorder.record(stubEnvelope("disaster:messages:list:seoul"), json);

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo(json);
    }

    @Test
    void record_appendsMultipleFailuresAsJsonl() throws IOException {
        Path file = tempDir.resolve("failures.jsonl");
        CacheRegenerationPublishFailureRecorder recorder =
                new CacheRegenerationPublishFailureRecorder(file.toString());

        String json1 = "{\"eventId\":\"id-1\"}";
        String json2 = "{\"eventId\":\"id-2\"}";
        recorder.record(stubEnvelope("shelter:status:1"), json1);
        recorder.record(stubEnvelope("shelter:status:2"), json2);

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(json1);
        assertThat(lines.get(1)).isEqualTo(json2);
    }

    @Test
    void record_createsParentDirectoriesIfAbsent() throws IOException {
        Path file = tempDir.resolve("nested/dir/failures.jsonl");
        CacheRegenerationPublishFailureRecorder recorder =
                new CacheRegenerationPublishFailureRecorder(file.toString());

        recorder.record(stubEnvelope("environment:weather:seoul"), "{\"eventId\":\"id-3\"}");

        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void record_writeFailure_doesNotPropagateException() {
        // point to a path that cannot be created (file as directory component)
        Path file = tempDir.resolve("not-a-dir.txt");
        CacheRegenerationPublishFailureRecorder recorder =
                new CacheRegenerationPublishFailureRecorder(file.resolve("child.jsonl").toString());

        // create a file at the "directory" path so subdirectory creation fails
        assertThatCode(() -> {
            Files.writeString(file, "block");
            recorder.record(stubEnvelope("disaster:messages:list:seoul"), "{\"eventId\":\"id-x\"}");
        }).doesNotThrowAnyException();
    }

    @Test
    void record_malformedFilePath_doesNotPropagateException() {
        // null byte in path triggers InvalidPathException (unchecked) inside Path.of()
        CacheRegenerationPublishFailureRecorder recorder =
                new CacheRegenerationPublishFailureRecorder("/tmp/invalid\0path/failures.jsonl");

        assertThatCode(() -> recorder.record(stubEnvelope("disaster:messages:list:seoul"), "{}"))
                .doesNotThrowAnyException();
    }
}
