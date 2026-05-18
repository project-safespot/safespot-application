package com.safespot.prescalingcontroller.service;

public record RestoreNormalResult(
        boolean success,
        String stage,
        String message
) {
    public static RestoreNormalResult success(String message) {
        return new RestoreNormalResult(true, "COMPLETED", message);
    }

    public static RestoreNormalResult failure(String stage, String message) {
        return new RestoreNormalResult(false, stage, message);
    }
}
