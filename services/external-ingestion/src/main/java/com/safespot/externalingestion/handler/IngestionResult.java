package com.safespot.externalingestion.handler;

import lombok.Getter;

@Getter
public class IngestionResult {
    public enum Status { SUCCESS, FAILED, SKIPPED, DUPLICATE }

    private final Status status;
    private final int recordsFetched;
    private final String message;

    private IngestionResult(Status status, int recordsFetched, String message) {
        this.status = status;
        this.recordsFetched = recordsFetched;
        this.message = message;
    }

    public static IngestionResult success(int records) {
        return new IngestionResult(Status.SUCCESS, records, null);
    }

    public static IngestionResult failed(String reason) {
        return new IngestionResult(Status.FAILED, 0, reason);
    }

    public static IngestionResult skipped(String reason) {
        return new IngestionResult(Status.SKIPPED, 0, reason);
    }

    public static IngestionResult duplicate() {
        return new IngestionResult(Status.DUPLICATE, 0, "payload_hash duplicate");
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
