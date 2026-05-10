package com.safespot.prescalingcontroller.service;

/**
 * Point-in-time snapshot of HPA spec values read in a single GET call.
 * Avoids reading the HPA twice per reconcile tick.
 */
public record HpaSnapshot(int minReplicas, int maxReplicas) {
}
