package jp.kohei.norisugoshi;

public final class RadiusSettings {
    public enum Ring {
        ALERT,
        REARM,
        MONITOR
    }

    public static final double MIN_ALERT_METERS = 100.0;
    public static final double MAX_ALERT_METERS = 5_000.0;
    public static final double MIN_REARM_METERS = 200.0;
    public static final double MAX_RADIUS_METERS = 20_000.0;
    public static final double MIN_MONITOR_METERS = 300.0;
    public static final double MIN_GAP_METERS = 100.0;

    private RadiusSettings() {
    }

    public static Values automaticForAlertRadius(double requestedMeters) {
        double alert = clamp(Double.isFinite(requestedMeters) ? requestedMeters : 500.0,
                MIN_ALERT_METERS, MAX_ALERT_METERS);
        // Keep GPS drift from re-enabling an alert near the same station.
        double rearm = alert + 1_000.0;
        double monitor = Math.max(5_000.0, rearm + 2_000.0);
        return new Values(alert, rearm, monitor);
    }

    public static Values normalized(double alert, double rearm, double monitor) {
        double safeAlert = clamp(
                Double.isFinite(alert) ? alert : 500.0,
                MIN_ALERT_METERS,
                MAX_ALERT_METERS
        );
        double safeRearm = clamp(
                Double.isFinite(rearm) ? rearm : 1_500.0,
                Math.max(MIN_REARM_METERS, safeAlert + MIN_GAP_METERS),
                MAX_RADIUS_METERS - MIN_GAP_METERS
        );
        double safeMonitor = clamp(
                Double.isFinite(monitor) ? monitor : 5_000.0,
                Math.max(MIN_MONITOR_METERS, safeRearm + MIN_GAP_METERS),
                MAX_RADIUS_METERS
        );
        return new Values(safeAlert, safeRearm, safeMonitor);
    }

    public static Values withChangedRadius(
            Values current,
            Ring ring,
            double requestedMeters
    ) {
        double rounded = roundForUi(requestedMeters);
        switch (ring) {
            case ALERT:
                return automaticForAlertRadius(rounded);
            case REARM:
                return new Values(
                        current.alertMeters,
                        clamp(
                                rounded,
                                Math.max(
                                        MIN_REARM_METERS,
                                        current.alertMeters + MIN_GAP_METERS
                                ),
                                current.monitorMeters - MIN_GAP_METERS
                        ),
                        current.monitorMeters
                );
            case MONITOR:
                return new Values(
                        current.alertMeters,
                        current.rearmMeters,
                        clamp(
                                rounded,
                                Math.max(
                                        MIN_MONITOR_METERS,
                                        current.rearmMeters + MIN_GAP_METERS
                                ),
                                MAX_RADIUS_METERS
                        )
                );
            default:
                throw new IllegalArgumentException("Unknown ring: " + ring);
        }
    }

    public static double minimumFor(Values values, Ring ring) {
        switch (ring) {
            case ALERT:
                return MIN_ALERT_METERS;
            case REARM:
                return Math.max(
                        MIN_REARM_METERS,
                        values.alertMeters + MIN_GAP_METERS
                );
            case MONITOR:
                return Math.max(
                        MIN_MONITOR_METERS,
                        values.rearmMeters + MIN_GAP_METERS
                );
            default:
                throw new IllegalArgumentException("Unknown ring: " + ring);
        }
    }

    public static double maximumFor(Values values, Ring ring) {
        switch (ring) {
            case ALERT:
                return MAX_ALERT_METERS;
            case REARM:
                return values.monitorMeters - MIN_GAP_METERS;
            case MONITOR:
                return MAX_RADIUS_METERS;
            default:
                throw new IllegalArgumentException("Unknown ring: " + ring);
        }
    }

    private static double roundForUi(double value) {
        double step;
        if (value <= 1_000.0) {
            step = 50.0;
        } else if (value <= 5_000.0) {
            step = 100.0;
        } else {
            step = 250.0;
        }
        return Math.round(value / step) * step;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Values {
        public final double alertMeters;
        public final double rearmMeters;
        public final double monitorMeters;

        public Values(double alertMeters, double rearmMeters, double monitorMeters) {
            this.alertMeters = alertMeters;
            this.rearmMeters = rearmMeters;
            this.monitorMeters = monitorMeters;
        }

        public double forRing(Ring ring) {
            switch (ring) {
                case ALERT:
                    return alertMeters;
                case REARM:
                    return rearmMeters;
                case MONITOR:
                    return monitorMeters;
                default:
                    throw new IllegalArgumentException("Unknown ring: " + ring);
            }
        }
    }
}
