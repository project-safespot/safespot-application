package com.safespot.asyncworker.envelope;

public enum EventType {
    EvacuationEntryCreated,
    EvacuationEntryExited,
    EvacuationEntryUpdated,
    ShelterUpdated,
    DisasterDataCollected,
    EnvironmentDataCollected,
    CacheRegenerationRequested;

    public static EventType from(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown eventType: " + value, e);
        }
    }
}
