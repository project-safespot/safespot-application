package com.safespot.scenariosimulator.domain.enums;

public enum DisasterLevel {
    LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

    public final int rank;

    DisasterLevel(int rank) {
        this.rank = rank;
    }
}
