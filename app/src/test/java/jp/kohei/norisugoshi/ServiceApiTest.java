package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.os.Looper;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.tasks.Task;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class ServiceApiTest {
    @Test
    public void googleLocationCallsUseActualTaskSignatures() throws Exception {
        assertEquals(Task.class, FusedLocationProviderClient.class
                .getMethod("removeLocationUpdates", LocationCallback.class).getReturnType());
        assertEquals(Task.class, FusedLocationProviderClient.class
                .getMethod("requestLocationUpdates", LocationRequest.class, LocationCallback.class,
                        Looper.class).getReturnType());
    }

    @Test
    public void serviceStopPathDoesNotCrash() {
        TravelMonitorService service = Robolectric.buildService(TravelMonitorService.class)
                .create().get();
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(
                new Intent().setAction(TravelMonitorService.ACTION_STOP_ALL), 0, 1));
        service.onDestroy();
    }

    @Test public void geofenceArrivalDisarmsAndKeepsAnExistingAlarmRunning() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        PlaceFixtures.install(context);
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.location.LocationManager.class)).setLocationEnabled(true);
        Station station = StationRepository.findById(context, StationRepository.ASAKADAI.id);
        MonitorStateStore.setArmed(context, station, true);
        TravelMonitorService service = Robolectric.buildService(TravelMonitorService.class).create().get();
        try {
            Intent arrival = new Intent().setAction(TravelMonitorService.ACTION_ARRIVAL_ALERT)
                    .putExtra(TravelMonitorService.EXTRA_STATION_ID, station.id);
            assertEquals(android.app.Service.START_REDELIVER_INTENT, service.onStartCommand(arrival, 0, 1));
            org.junit.Assert.assertFalse(MonitorStateStore.isArmed(context, station));
            assertEquals(MonitorStateStore.MODE_ALERTING, MonitorStateStore.getActiveMode(context));
            assertEquals(android.app.Service.START_REDELIVER_INTENT, service.onStartCommand(arrival, 0, 2));
            assertEquals(MonitorStateStore.MODE_ALERTING, MonitorStateStore.getActiveMode(context));
        } finally { service.onDestroy(); }
    }
}
