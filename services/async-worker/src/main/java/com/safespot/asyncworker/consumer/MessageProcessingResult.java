package com.safespot.asyncworker.consumer;

public record MessageProcessingResult(
    String messageId,
    boolean success,
    String failureReason
) {
    public static MessageProcessingResult success(String messageId) {
        return new MessageProcessingResult(messageId, true, null);
    }

    public static MessageProcessingResult failure(String messageId, String reason) {
        return new MessageProcessingResult(messageId, false, reason);
    }
}
