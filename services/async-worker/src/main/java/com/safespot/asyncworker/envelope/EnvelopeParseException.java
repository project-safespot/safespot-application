package com.safespot.asyncworker.envelope;

public class EnvelopeParseException extends RuntimeException {

    public EnvelopeParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public EnvelopeParseException(String message) {
        super(message);
    }
}
