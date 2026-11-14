package jp.kohei.norisugoshi;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationStateTest {
    private Context context;
    private Station station;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear()
                .putInt("state_schema", 5).commit();
        GeofenceRegistrar.resetRegistrationState();
        PlaceFixtures.install(context);
        station = StationRepository.findById(context, StationRepository.ASAKADAI.id);
    }

    @Test public void updateEnablesOldDisabledStationsWithoutLosingPlacesOrWaitingState() {
        Station disabled = new Station(station.id, station.name, station.latitude, station.longitude,
                station.alertRadiusMeters, station.rearmRadiusMeters, station.monitorRadiusMeters,
                false, station.activationAreas);
        StationRepository.upsert(context, disabled);
        context.getSharedPreferences("monitor_state", 0).edit().putInt("state_schema", 3).commit();
        MonitorStateStore.setArmed(context, station, true);
        context.getSharedPreferences("monitor_state", 0).edit().putBoolean("enabled", false).commit();
        try (ActivityController<MainActivity> ignored = Robolectric.buildActivity(MainActivity.class).setup()) {
            Station restored = StationRepository.findById(context, station.id);
            assertTrue(restored.enabled);
            assertEquals(station.latitude, restored.latitude, 0);
            assertEquals(station.alertRadiusMeters, restored.alertRadiusMeters, 0);
            assertEquals(station.activationAreas.get(1).id, restored.activationAreas.get(1).id);
            assertTrue(MonitorStateStore.isArmed(context, restored));
            assertFalse(context.getSharedPreferences("monitor_state", 0).contains("enabled"));
        }
    }

    @Test public void switchChangesWaitingStateAndKeepsItAfterReopening() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            Switch state = stateSwitch(controller.get());
            assertFalse(state.isChecked());
            state.performClick();
            assertTrue(MonitorStateStore.isArmed(context, station));
            assertTrue(stateSwitch(controller.get()).isChecked());
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(stateSwitch(controller.get()).isChecked());
        }
    }

    @Test public void turningTheSwitchOffKeepsEveryActivationAreaWatched() {
        MonitorStateStore.setArmed(context, station, true);
        MonitorStateStore.setArmed(context, StationRepository.IKEBUKURO, true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            stateSwitch(controller.get()).performClick();
            assertFalse(MonitorStateStore.isArmed(context, station));
            assertTrue(StationRepository.findById(context, station.id).enabled);
            assertTrue(MonitorStateStore.isArmed(context, StationRepository.IKEBUKURO));
            List<?> regions = ReflectionHelpers.callStaticMethod(GeofenceRegistrar.class, "buildGeofences",
                    ReflectionHelpers.ClassParameter.from(Context.class, context));
            assertEquals(12, regions.size());
        }
    }

    @Test public void manualOnRequestsLocationSoItDoesNotWaitForAnotherAreaEntry() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        ReflectionHelpers.setStaticField(GeofenceRegistrar.class, "registrationState",
                GeofenceRegistrar.STATE_REGISTERED);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            stateSwitch(controller.get()).performClick();
            Intent start = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(start);
            assertEquals(TravelMonitorService.ACTION_START_MONITOR, start.getAction());
            assertEquals(station.id, start.getStringExtra(TravelMonitorService.EXTRA_STATION_ID));
        }
    }

    @Test public void automaticActivationArrivalAndStopUpdateTheVisibleState() {
        TravelMonitorService service = Robolectric.buildService(TravelMonitorService.class).create().get();
        ReflectionHelpers.setField(service, "activeStation", station);
        ReflectionHelpers.setField(service, "currentMode", MonitorStateStore.MODE_MONITORING);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(stateSwitch(controller.get()).isChecked());
            ActivationArea area = station.activationAreas.get(0);
            locate(service, area.latitude, area.longitude);
            tick();
            assertTrue(stateSwitch(controller.get()).isChecked());
            locate(service, station.latitude, station.longitude);
            tick();
            assertFalse(MonitorStateStore.isArmed(context, station));
            assertNull(find(controller.get().findViewById(android.R.id.content),
                    station.name + "の通知待ちを切り替える"));
            View stop = find(controller.get().findViewById(android.R.id.content), station.name + "の振動を止める");
            assertNotNull(stop);
            assertTrue(stop.performClick());
            Intent stopIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(stopIntent);
            service.onStartCommand(stopIntent, 0, 1);
            tick();
            assertFalse(stateSwitch(controller.get()).isChecked());
            assertEquals(MonitorStateStore.MODE_NONE, MonitorStateStore.getActiveMode(context));
            assertEquals(2, StationRepository.getConfiguredEnabled(context).size());
        } finally {
            service.onDestroy();
        }
    }

    private Switch stateSwitch(MainActivity activity) {
        View view = find(activity.findViewById(android.R.id.content), station.name + "の通知待ちを切り替える");
        assertNotNull(view);
        return (Switch) view;
    }

    private void tick() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); }

    private void locate(TravelMonitorService service, double latitude, double longitude) {
        Location fix = new Location("test");
        fix.setLatitude(latitude); fix.setLongitude(longitude); fix.setAccuracy(10);
        ReflectionHelpers.callInstanceMethod(service, "onLocation",
                ReflectionHelpers.ClassParameter.from(Location.class, fix));
    }

    private View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
