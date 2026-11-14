package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutomaticProtectionTest {
    private Context context;
    private Station station;

    @Before
    public void clearSettings() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        PlaceFixtures.install(context);
        station = StationRepository.findById(context, StationRepository.ASAKADAI.id);
    }

    @Test
    public void returningInsideTheCircleAfterStoppingDoesNotAlertAgain() {
        TravelMonitorService service = monitoringService();
        try {
            MonitorStateStore.setArmed(context, station, false);
            ReflectionHelpers.setField(service, "hasSeenOutsideAlertRadius", true);
            ReflectionHelpers.setField(service, "previousDistanceMeters", Double.valueOf(650));
            locate(service, 450);
            assertEquals(MonitorStateStore.MODE_MONITORING,
                    ReflectionHelpers.getField(service, "currentMode"));
            assertFalse(MonitorStateStore.isArmed(context, station));
        } finally {
            service.onDestroy();
        }
    }

    @Test
    public void aGoodFixAfterUndergroundTravelRearmsAndAlertsOnTheNextArrival() {
        TravelMonitorService service = monitoringService();
        try {
            MonitorStateStore.setArmed(context, station, false);
            locate(service, 3_000);
            assertTrue(MonitorStateStore.isArmed(context, station));
            locate(service, 600);
            locate(service, 450);
            assertEquals(MonitorStateStore.MODE_ALERTING,
                    ReflectionHelpers.getField(service, "currentMode"));
            assertFalse(MonitorStateStore.isArmed(context, station));
        } finally {
            service.onDestroy();
        }
    }

    private TravelMonitorService monitoringService() {
        TravelMonitorService service = Robolectric.buildService(TravelMonitorService.class).create().get();
        ReflectionHelpers.setField(service, "activeStation", station);
        ReflectionHelpers.setField(service, "currentMode", MonitorStateStore.MODE_MONITORING);
        return service;
    }

    @Test public void eitherConfiguredPlaceCanActivateTheSameDestination() {
        for (double position : new double[]{3_000, -3_000}) {
            TravelMonitorService service = monitoringService();
            try {
                MonitorStateStore.setArmed(context, station, false);
                locate(service, position);
                assertTrue(MonitorStateStore.isArmed(context, station));
                locate(service, 100);
                assertEquals(MonitorStateStore.MODE_ALERTING, ReflectionHelpers.getField(service, "currentMode"));
                assertFalse(MonitorStateStore.isArmed(context, station));
            } finally { service.onDestroy(); }
        }
    }

    @Test public void leavingDestinationWithoutVisitingAnyActivationPlaceDoesNotTurnOn() {
        TravelMonitorService service = monitoringService();
        try {
            locate(service, 1_000);
            locate(service, 100);
            assertFalse(MonitorStateStore.isArmed(context, station));
            assertEquals(MonitorStateStore.MODE_MONITORING, ReflectionHelpers.getField(service, "currentMode"));
        } finally { service.onDestroy(); }
    }

    @Test public void aNewApproachDoesNotCancelAnActiveAlarm() {
        TravelMonitorService service = monitoringService();
        try {
            ReflectionHelpers.setField(service, "currentMode", MonitorStateStore.MODE_ALERTING);
            service.onStartCommand(new android.content.Intent().setAction(TravelMonitorService.ACTION_START_MONITOR)
                    .putExtra(TravelMonitorService.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id), 0, 1);
            assertEquals(MonitorStateStore.MODE_ALERTING, ReflectionHelpers.getField(service, "currentMode"));
        } finally { service.onDestroy(); }
    }

    private void locate(TravelMonitorService service, double metersNorth) {
        Location fix = new Location("test");
        fix.setLatitude(station.latitude + Math.toDegrees(metersNorth / 6_371_000.0));
        fix.setLongitude(station.longitude);
        fix.setAccuracy(10);
        ReflectionHelpers.callInstanceMethod(service, "onLocation",
                ReflectionHelpers.ClassParameter.from(Location.class, fix));
    }
}
