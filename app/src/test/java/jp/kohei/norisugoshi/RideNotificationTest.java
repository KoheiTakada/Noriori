package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=37)
@LooperMode(LooperMode.Mode.PAUSED)
public class RideNotificationTest {
    private Context c;
    @Before public void setup() {
        c=RuntimeEnvironment.getApplication();
        RideCoordinator.resetForTest();RideStore.clear(c);
        c.getSharedPreferences("monitor_state",0).edit().clear().commit();
        LastTrainStore.prefs(c).edit().clear().commit();
        c.getSharedPreferences(PermissionSetup.PREFS,0).edit().clear().commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.ACTIVITY_RECOGNITION);
        Shadows.shadowOf(c.getSystemService(LocationManager.class)).setLocationEnabled(true);
        RideNotification.channels(c);
    }
    @After public void cleanup() throws Exception {RideCoordinator.awaitIdleForTest();RideCoordinator.resetForTest();}
    @Test public void nativeApi37NotificationCarriesTheRealStationNameAndPromotionRequest() {
        Notification n=RideNotification.station(c,"池袋駅",45_000);
        assertEquals("池袋駅",n.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertTrue(n.extras.getBoolean(RideNotification.PROMOTED_EXTRA));
        assertEquals("池袋",n.getShortCriticalText());
        assertTrue(n.hasPromotableCharacteristics());
        assertTrue((n.flags&Notification.FLAG_ONGOING_EVENT)!=0);
        assertFalse((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0);
        assertNull(n.contentView);assertEquals(45_000,n.getTimeoutAfter());
        assertNotNull(n.deleteIntent);assertEquals("非表示",n.actions[0].title);
        assertEquals(NotificationManager.IMPORTANCE_LOW,c.getSystemService(NotificationManager.class)
                .getNotificationChannel(RideNotification.HUB_CHANNEL).getImportance());
    }
    @Test public void idleStateNeverPostsALiveUpdate() {
        RideCoordinator.update(c);assertNull(notification());assertFalse(RideCoordinator.shouldTrack(c));
    }
    @Test public void freshHubThenStaleGpsRemovesTheLiveUpdate() {
        setHub();RideCoordinator.update(c);assertNotNull(notification());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(45));
        RideCoordinator.update(c);assertNull(notification());assertTrue(RideCoordinator.engine(c).riding);
    }
    @Test public void dismissalDoesNotChangeArrivalStationsOrArming() {
        Station station=StationRepository.getAll(c).get(0);MonitorStateStore.setArmed(c,station,true);
        setHub();RideCoordinator.update(c);
        new RideReceiver().onReceive(c,new Intent(c,RideReceiver.class).setAction(RideReceiver.DISMISS));
        assertNull(notification());assertTrue(MonitorStateStore.isArmed(c,station));
        RideCoordinator.update(c);assertNull(notification());assertTrue(RideCoordinator.engine(c).riding);
    }
    @Test public void processRestoreRetainsTripButNeverRestoresStationText() {
        setHub();RideStore.save(c,RideCoordinator.engine(c));RideCoordinator.resetForTest();
        assertTrue(RideCoordinator.engine(c).riding);assertNull(RideCoordinator.engine(c).hubName);
        RideCoordinator.update(c);assertNull(notification());
    }
    @Test public void rebootDoesNotRestoreAnOldTrip() {
        setHub();RideStore.save(c,RideCoordinator.engine(c));
        c.getSharedPreferences(RideStore.PREFS,0).edit().putLong("boot",1).commit();
        RideCoordinator.resetForTest();assertFalse(RideCoordinator.engine(c).riding);
    }
    @Test public void arrivalAlertTakesPrecedenceOverStationText() {
        setHub();RideCoordinator.update(c);assertNotNull(notification());
        Station station=StationRepository.getAll(c).get(0);
        MonitorStateStore.setActive(c,MonitorStateStore.MODE_ALERTING,station);
        RideCoordinator.update(c);assertNull(notification());
    }
    @Test public void lossOfLocationPermissionRemovesTheLiveUpdate() {
        setHub();RideCoordinator.update(c);assertNotNull(notification());
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        RideCoordinator.update(c);assertNull(notification());
    }
    @Test public void serviceDestroyClearsItsNotificationAndLeavesSavedStationsAlone() {
        setHub();String id=StationRepository.getAll(c).get(0).id;
        org.robolectric.android.controller.ServiceController<RideTrackingService> controller=
                Robolectric.buildService(RideTrackingService.class).create();
        controller.get().onStartCommand(new Intent(c,RideTrackingService.class),0,1);
        RideCoordinator.update(c);assertNotNull(notification());controller.destroy();
        assertNull(notification());assertNotNull(StationRepository.findById(c,id));
    }
    @Test public void initialLaunchRequestsPhysicalActivityWithoutPressingAButton() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        try (var activity=Robolectric.buildActivity(MainActivity.class).setup()) {
            android.widget.Button button=ReflectionHelpers.getField(activity.get(),"activityButton");
            assertEquals(android.view.View.VISIBLE,button.getVisibility());
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var request=Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertArrayEquals(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},request.requestedPermissions);
        }
    }
    @Test public void openingLastTrainTabStillAutomaticallyRequestsPhysicalActivity() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        Intent intent=new Intent(c,MainActivity.class).putExtra("last_train",true);
        try (var activity=Robolectric.buildActivity(MainActivity.class,intent).setup()) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1,ReflectionHelpers.<Integer>getField(activity.get(),"selectedFeature").intValue());
            var request=Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertArrayEquals(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},request.requestedPermissions);
        }
    }
    @Test public void revokingActivityPermissionWhileTheProbeIsPendingDoesNotCrash() {
        ReflectionHelpers.setStaticField(RideCoordinator.class,"probing",true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        RideCoordinator.finishProbe(c);
        assertFalse(ReflectionHelpers.<Boolean>getStaticField(RideCoordinator.class,"probing"));
    }
    @Test public void realYamanoteGeometryProducesOnlyTheHubStationAndClearsItOnGpsLoss() throws Exception {
        // MLIT N02-25 vertices, with synthetic times/speeds: this is a replay,
        // not recorded device GPS or an assertion of field detection accuracy.
        gps(35.72145,139.70660,15);advance(20);gps(35.72406,139.70802,15);
        assertTrue(RideCoordinator.engine(c).riding);assertNull(notification());
        advance(20);gps(35.72618,139.70915,15);assertNull(notification());
        advance(40);gps(35.730275,139.711085,0);assertNull(notification());
        advance(5);gps(35.730275,139.711085,0);
        assertEquals("池袋",notification().getShortCriticalText());
        advance(5);Location bad=new Location("gps");bad.setLatitude(35.730275);bad.setLongitude(139.711085);
        bad.setAccuracy(300);bad.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        RideCoordinator.observe(c,bad);RideCoordinator.awaitIdleForTest();
        assertNull(notification());assertTrue(RideCoordinator.engine(c).riding);
    }
    private void gps(double lat,double lon,float speed) throws Exception {
        Location l=new Location("gps");l.setLatitude(lat);l.setLongitude(lon);l.setAccuracy(10);
        l.setSpeed(speed);l.setSpeedAccuracyMetersPerSecond(0.3f);l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        l.setTime(System.currentTimeMillis());RideCoordinator.observe(c,l);RideCoordinator.awaitIdleForTest();
    }
    private void advance(int seconds) {org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds));}
    private void setHub() {
        RideEngine e=RideCoordinator.engine(c);long now=SystemClock.elapsedRealtime();
        e.riding=true;e.started=now;e.lastRailMotion=now;e.hubName="池袋駅";e.hubAt=now;
    }
    private Notification notification() {return Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(RideNotification.HUB_ID);}
}
