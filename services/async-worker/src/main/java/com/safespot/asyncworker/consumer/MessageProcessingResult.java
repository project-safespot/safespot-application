package com.safespot.asyncworker.consumer;

public record MessageProcessingResult(
    String messageId,
    boolean success,
    String failureReason,
    String eventType
) {
    public static MessageProcessingResult success(String messageId, String eventType) {
        return new MessageProcessingResult(messageId, true, null, eventType);
    }

    public static MessageProcessingResult failure(String messageId, String reason, String eventType) {
        return new MessageProcessingResult(messageId, false, reason, eventType);
    }
}
