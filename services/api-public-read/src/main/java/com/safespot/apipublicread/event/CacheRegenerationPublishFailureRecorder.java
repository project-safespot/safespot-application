package com.safespot.apipublicread.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@RequiredArgsConstructor
public class CacheRegenerationPublishFailureRecorder {

    private final String filePath;

    public void record(CacheRegenerationEnvelope envelope, String envelopeJson) {
        try {
            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, envelopeJson + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("[CacheRegen] Failed to record publish failure — eventId={} idempotencyKey={} cacheKeyFamily={}: {}",
                    envelope.eventId(), envelope.idempotencyKey(),
                    envelope.payload().cacheKeyFamily(), e.getMessage());
        }
    }

    public void record(CacheRegenerationEnvelope envelope, String envelopeJson,
                       QueueType queueType, String envelopeType) {
        try {
            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, envelopeJson + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("[CacheRegen] Failed to record publish failure — eventId={} idempotencyKey={} cacheKeyFamily={} queueType={} envelopeType={}: {}",
                    envelope.eventId(), envelope.idempotencyKey(),
                    envelope.payload().cacheKeyFamily(),
                    queueType != null ? queueType.label() : "unknown",
                    envelopeType != null ? envelopeType : "unknown",
                    e.getMessage());
        }
    }
}
