package jp.kohei.norisugoshi;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ActivationArea {
    public final String name;
    public final String id;
    public final double latitude;
    public final double longitude;
    public final double radiusMeters;

    public ActivationArea(String name, double latitude, double longitude, double radiusMeters) {
        if (!Double.isFinite(latitude) || latitude < -85 || latitude > 85
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid activation coordinates");
        }
        this.name = Objects.requireNonNull(name);
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = RadiusSettings.automaticForAlertRadius(radiusMeters).alertMeters;
        this.id = UUID.nameUUIDFromBytes((name + ":" + latitude + ":" + longitude + ":" + this.radiusMeters)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public boolean isSeparateFrom(Station station) {
        return Geo.distanceMeters(latitude, longitude, station.latitude, station.longitude)
                > radiusMeters + station.alertRadiusMeters + RadiusSettings.MIN_GAP_METERS;
    }
}
