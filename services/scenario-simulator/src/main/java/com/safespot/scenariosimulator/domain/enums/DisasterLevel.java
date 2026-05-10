package com.safespot.scenariosimulator.domain.enums;

public enum DisasterLevel {
    LOW(1, "INTEREST"),
    MEDIUM(2, "CAUTION"),
    HIGH(3, "WARNING"),
    CRITICAL(4, "CRITICAL");

    public final int rank;
    // DB disaster_alert.level check constraint only allows: INTEREST, CAUTION, WARNING, CRITICAL
    public final String canonicalLevel;

    DisasterLevel(int rank, String canonicalLevel) {
        this.rank = rank;
        this.canonicalLevel = canonicalLevel;
    }
}
