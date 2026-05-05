package com.safespot.asyncworker.exception;

public class PermanentFailureException extends RuntimeException {
    public PermanentFailureException(String message) {
        super(message);
    }
    public PermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
