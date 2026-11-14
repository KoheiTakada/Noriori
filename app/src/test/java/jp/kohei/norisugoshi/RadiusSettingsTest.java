package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadiusSettingsTest {
    @Test
    public void normalizesInvalidOrdering() {
        RadiusSettings.Values values = RadiusSettings.normalized(700.0, 500.0, 400.0);

        assertEquals(700.0, values.alertMeters, 0.0);
        assertEquals(800.0, values.rearmMeters, 0.0);
        assertEquals(900.0, values.monitorMeters, 0.0);
    }

    @Test
    public void expandingAlertAutomaticallyMovesTheOtherBoundaries() {
        RadiusSettings.Values initial = RadiusSettings.normalized(500.0, 1_500.0, 5_000.0);
        RadiusSettings.Values changed = RadiusSettings.withChangedRadius(
                initial,
                RadiusSettings.Ring.ALERT,
                4_000.0
        );

        assertEquals(4_000.0, changed.alertMeters, 0.0);
        assertEquals(5_000.0, changed.rearmMeters, 0.0);
        assertEquals(7_000.0, changed.monitorMeters, 0.0);
    }

    @Test
    public void automaticBoundariesKeepGpsDriftOutsideTheNotificationCircle() {
        for (double radius : new double[]{100, 250, 500, 587, 2_000, 5_000}) {
            RadiusSettings.Values values = RadiusSettings.automaticForAlertRadius(radius);
            assertEquals(radius, values.alertMeters, 0.0);
            assertTrue(values.rearmMeters - values.alertMeters
                    > ArrivalDecisionEngine.MAX_ACCEPTED_ACCURACY_METERS);
            assertTrue(values.monitorMeters > values.rearmMeters);
            assertTrue(values.monitorMeters <= RadiusSettings.MAX_RADIUS_METERS);
        }
    }

    @Test
    public void invalidNotificationRadiusUsesSafeBounds() {
        assertEquals(500, RadiusSettings.automaticForAlertRadius(Double.NaN).alertMeters, 0);
        assertEquals(100, RadiusSettings.automaticForAlertRadius(-500).alertMeters, 0);
        assertEquals(5_000, RadiusSettings.automaticForAlertRadius(50_000).alertMeters, 0);
    }

    @Test
    public void rearmRingStaysBetweenOtherRings() {
        RadiusSettings.Values initial = RadiusSettings.normalized(500.0, 1_500.0, 5_000.0);

        assertEquals(
                600.0,
                RadiusSettings.withChangedRadius(
                        initial,
                        RadiusSettings.Ring.REARM,
                        100.0
                ).rearmMeters,
                0.0
        );
        assertEquals(
                4_900.0,
                RadiusSettings.withChangedRadius(
                        initial,
                        RadiusSettings.Ring.REARM,
                        8_000.0
                ).rearmMeters,
                0.0
        );
    }

    @Test
    public void monitorRingCannotCrossRearmRing() {
        RadiusSettings.Values initial = RadiusSettings.normalized(500.0, 1_500.0, 5_000.0);
        RadiusSettings.Values changed = RadiusSettings.withChangedRadius(
                initial,
                RadiusSettings.Ring.MONITOR,
                800.0
        );

        assertEquals(1_600.0, changed.monitorMeters, 0.0);
    }
}
