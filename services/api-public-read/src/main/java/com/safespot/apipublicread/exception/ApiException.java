package com.safespot.apipublicread.exception;

public class ApiException extends RuntimeException {

    public final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
