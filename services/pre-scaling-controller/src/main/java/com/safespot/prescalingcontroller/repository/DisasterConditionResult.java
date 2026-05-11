package com.safespot.prescalingcontroller.repository;

/**
 * Result of a DB disaster condition query.
 *
 * ACTIVE  — qualifying alert confirmed in DB
 * INACTIVE — no qualifying alert in DB (positive result, not an error)
 * UNKNOWN — DB query failed; state cannot be determined
 *
 * Recovery patch is only performed when the result is INACTIVE.
 * UNKNOWN must never trigger a recovery (§ HIGH RISK #7).
 */
public enum DisasterConditionResult {
    ACTIVE,
    INACTIVE,
    UNKNOWN
}
