package com.safespot.externalingestion.queue;

import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;

@Component
public class InMemoryNormalizationQueue implements NormalizationQueue {

    private final LinkedBlockingQueue<NormalizationMessage> queue = new LinkedBlockingQueue<>();

    @Override
    public void publish(NormalizationMessage message) {
        queue.offer(message);
    }

    @Override
    public NormalizationMessage poll() {
        return queue.poll();
    }
}
