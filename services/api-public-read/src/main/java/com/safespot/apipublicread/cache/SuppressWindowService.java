package com.safespot.apipublicread.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SuppressWindowService {

    private static final long SUPPRESS_WINDOW_MS = 10_000L;

    private final ConcurrentHashMap<String, Long> lastEmitTime = new ConcurrentHashMap<>();

    public boolean shouldPublish(String key) {
        long now = System.currentTimeMillis();
        Long last = lastEmitTime.get(key);
        return last == null || (now - last) >= SUPPRESS_WINDOW_MS;
    }

    public void markEmitted(String key) {
        lastEmitTime.put(key, System.currentTimeMillis());
    }

    public boolean tryPublish(String key) {
        long now = System.currentTimeMillis();
        boolean[] emitted = {false};
        lastEmitTime.compute(key, (k, last) -> {
            if (last == null || (now - last) >= SUPPRESS_WINDOW_MS) {
                emitted[0] = true;
                return now;
            }
            return last;
        });
        return emitted[0];
    }
}
