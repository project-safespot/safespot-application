package com.safespot.externalingestion.normalizer;

import lombok.Getter;

import java.util.List;

@Getter
public class NormalizationResult {
    private final int succeeded;
    private final int failed;
    private final List<String> errors;

    private NormalizationResult(int succeeded, int failed, List<String> errors) {
        this.succeeded = succeeded;
        this.failed = failed;
        this.errors = errors;
    }

    public static NormalizationResult of(int succeeded, int failed, List<String> errors) {
        return new NormalizationResult(succeeded, failed, errors);
    }

    public static NormalizationResult success(int count) {
        return new NormalizationResult(count, 0, List.of());
    }

    public static NormalizationResult failure(String error) {
        return new NormalizationResult(0, 1, List.of(error));
    }

    public boolean hasFailures() {
        return failed > 0;
    }
}
