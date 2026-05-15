package com.safespot.asyncworker.service.shelter.geo;

public final class TileCoordinateCalculator {

    private static final double MAX_LATITUDE = 85.05112878d;
    private static final double MIN_LATITUDE = -85.05112878d;

    private TileCoordinateCalculator() {}

    public static TileCoordinate from(double latitude, double longitude, int zoom) {
        double normalizedLatitude = Math.max(MIN_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        double normalizedLongitude = ((longitude + 180d) % 360d + 360d) % 360d - 180d;

        double latitudeRadians = Math.toRadians(normalizedLatitude);
        double tilesPerAxis = 1 << zoom;

        int x = (int) Math.floor((normalizedLongitude + 180d) / 360d * tilesPerAxis);
        double mercator = Math.log(Math.tan(Math.PI / 4d + latitudeRadians / 2d));
        int y = (int) Math.floor((1d - mercator / Math.PI) / 2d * tilesPerAxis);

        int maxIndex = (int) tilesPerAxis - 1;
        return new TileCoordinate(
            zoom,
            Math.max(0, Math.min(maxIndex, x)),
            Math.max(0, Math.min(maxIndex, y))
        );
    }
}
