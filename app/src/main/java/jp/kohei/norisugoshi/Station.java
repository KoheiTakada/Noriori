package jp.kohei.norisugoshi;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class Station {
    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final double alertRadiusMeters;
    public final double rearmRadiusMeters;
    public final double monitorRadiusMeters;
    public final boolean enabled;
    public final ActivationArea activationArea;
    public final List<ActivationArea> activationAreas;

    public Station(String id, String name, double latitude, double longitude) {
        this(id, name, latitude, longitude, 500.0, 1_500.0, 5_000.0, true);
    }

    public Station(
            String id,
            String name,
            double latitude,
            double longitude,
            double alertRadiusMeters,
            double rearmRadiusMeters,
            double monitorRadiusMeters,
            boolean enabled
    ) {
        this(id, name, latitude, longitude, alertRadiusMeters, rearmRadiusMeters,
                monitorRadiusMeters, enabled, Collections.emptyList());
    }

    public Station(String id, String name, double latitude, double longitude,
                   double alertRadiusMeters, double rearmRadiusMeters, double monitorRadiusMeters,
                   boolean enabled, ActivationArea activationArea) {
        this(id, name, latitude, longitude, alertRadiusMeters, rearmRadiusMeters, monitorRadiusMeters,
                enabled, activationArea == null ? Collections.emptyList() : Collections.singletonList(activationArea));
    }

    public Station(String id, String name, double latitude, double longitude,
                   double alertRadiusMeters, double rearmRadiusMeters, double monitorRadiusMeters,
                   boolean enabled, List<ActivationArea> activationAreas) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.latitude = latitude;
        this.longitude = longitude;
        this.alertRadiusMeters = alertRadiusMeters;
        this.rearmRadiusMeters = rearmRadiusMeters;
        this.monitorRadiusMeters = monitorRadiusMeters;
        this.enabled = enabled;
        this.activationAreas = Collections.unmodifiableList(new ArrayList<>(activationAreas));
        this.activationArea = this.activationAreas.isEmpty() ? null : this.activationAreas.get(0);
    }

    public boolean isConfigured() {
        if (activationAreas.isEmpty()) return false;
        for (ActivationArea area : activationAreas) if (!area.isSeparateFrom(this)) return false;
        return true;
    }
}
