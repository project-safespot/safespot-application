package com.safespot.externalingestion.client;

import lombok.Getter;

@Getter
public class ExternalApiException extends RuntimeException {

    public enum ErrorType { NETWORK, TIMEOUT, CLIENT_ERROR, SERVER_ERROR }

    private final ErrorType errorType;
    private final Integer httpStatus;

    public ExternalApiException(String message, ErrorType errorType, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public ExternalApiException(String message, ErrorType errorType, Integer httpStatus) {
        super(message);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }
}
