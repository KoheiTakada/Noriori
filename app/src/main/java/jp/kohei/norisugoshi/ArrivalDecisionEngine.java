package jp.kohei.norisugoshi;

public final class ArrivalDecisionEngine {
    public static final float MAX_ACCEPTED_ACCURACY_METERS = 500.0f;
    public static final double MIN_APPROACH_DELTA_METERS = 25.0;
    public static final long MONITOR_TIMEOUT_MILLIS = 20L * 60L * 1_000L;

    private ArrivalDecisionEngine() {
    }

    public static boolean isAccurateEnough(float accuracyMeters) {
        return accuracyMeters >= 0.0f
                && accuracyMeters <= MAX_ACCEPTED_ACCURACY_METERS;
    }

    public static boolean shouldAlert(
            double currentDistanceMeters,
            float accuracyMeters,
            double alertRadiusMeters,
            boolean armed
    ) {
        return armed && isAccurateEnough(accuracyMeters) && currentDistanceMeters <= alertRadiusMeters;
    }

    public static boolean shouldActivate(double distanceMeters, float accuracyMeters, double radiusMeters) {
        return isAccurateEnough(accuracyMeters) && distanceMeters <= radiusMeters;
    }
}
