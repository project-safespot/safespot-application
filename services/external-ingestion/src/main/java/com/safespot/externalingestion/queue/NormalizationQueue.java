package com.safespot.externalingestion.queue;

public interface NormalizationQueue {
    void publish(NormalizationMessage message);
    NormalizationMessage poll();
}
