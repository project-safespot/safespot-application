package com.safespot.asyncworker.exception;

public class RedisCacheException extends RuntimeException {

    public RedisCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public RedisCacheException(String message) {
        super(message);
    }
}
