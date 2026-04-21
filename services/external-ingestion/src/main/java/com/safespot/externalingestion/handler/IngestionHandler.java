package com.safespot.externalingestion.handler;

public interface IngestionHandler {
    String getSourceCode();
    IngestionResult execute();
    boolean isEnabled();
}
