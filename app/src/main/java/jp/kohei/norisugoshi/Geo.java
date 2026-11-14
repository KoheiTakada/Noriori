package jp.kohei.norisugoshi;

public final class Geo {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private Geo() {
    }
    static double[] destination(double latitude, double longitude, double meters, double bearingDegrees) {
        double lat=Math.toRadians(latitude),lon=Math.toRadians(longitude),angle=meters/EARTH_RADIUS_METERS;
        double bearing=Math.toRadians(bearingDegrees);
        double targetLat=Math.asin(Math.sin(lat)*Math.cos(angle)+Math.cos(lat)*Math.sin(angle)*Math.cos(bearing));
        double targetLon=lon+Math.atan2(Math.sin(bearing)*Math.sin(angle)*Math.cos(lat),
                Math.cos(angle)-Math.sin(lat)*Math.sin(targetLat));
        return new double[]{Math.toDegrees(targetLat),((Math.toDegrees(targetLon)+540)%360)-180};
    }

    public static double distanceMeters(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2
    ) {
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = Math.toRadians(latitude2 - latitude1);
        double deltaLon = Math.toRadians(longitude2 - longitude1);

        double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
