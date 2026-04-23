package com.safespot.apipublicread.service;

public final class CongestionCalculator {

    private CongestionCalculator() {}

    public static String calculate(int capacity, int currentOccupancy) {
        if (capacity <= 0) return "AVAILABLE";
        double ratio = (double) currentOccupancy / capacity;
        if (ratio >= 1.0) return "FULL";
        if (ratio >= 0.75) return "CROWDED";
        if (ratio >= 0.50) return "NORMAL";
        return "AVAILABLE";
    }

    public static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) (R * c);
    }

    public static double metersToDegreeLat(int meters) {
        return meters / 111_000.0;
    }

    public static double metersToDegreeeLng(int meters, double lat) {
        return meters / (111_000.0 * Math.cos(Math.toRadians(lat)));
    }
}
