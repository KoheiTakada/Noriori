package jp.kohei.norisugoshi;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class PermissionSetupTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PermissionSetup.PREFS, 0).edit().clear().commit();
        grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACTIVITY_RECOGNITION);
        Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        Shadows.shadowOf(context.getSystemService(PowerManager.class))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
        PromotionManager.allowed = true;
    }

    @Test public void upgradedLaunchAutomaticallyRequestsOnlyTheMissingRuntimePermission() {
        deny(Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle();
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
        }
    }

    @Test
    public void freshInstallOpensEveryMissingPermissionInOrderWithoutAppButtonPresses() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACTIVITY_RECOGNITION);
        Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(false);
        Shadows.shadowOf(context.getSystemService(PowerManager.class))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        PromotionManager.allowed = false;
        assertFalse(PermissionUtils.hasFineLocation(context));
        assertFalse(PermissionUtils.hasBackgroundLocation(context));
        assertFalse(PermissionUtils.hasNotificationPermission(context));
        RideCoordinator.resetForTest();
        LastTrainStore.prefs(context).edit().clear().commit();
        try (var controller = Robolectric.buildActivity(MainActivity.class).create().start()) {
            MainActivity host = controller.get();
            Shadows.shadowOf(host).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION);
            assertFalse("Activity must start without location access", PermissionUtils.hasFineLocation(host));
            assertFalse("Activity must start without background access", PermissionUtils.hasBackgroundLocation(host));
            assertFalse("Activity must start without notifications", PermissionUtils.hasNotificationPermission(host));
            controller.resume(); idle();
            assertRequest(host, PermissionSetup.LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION);
            grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
            runtimeResult(host, PermissionSetup.LOCATION);
            assertSettings(host, PermissionSetup.BACKGROUND, Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            settingsResult(host, PermissionSetup.BACKGROUND);
            assertRequest(host, PermissionSetup.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS);
            grant(Manifest.permission.POST_NOTIFICATIONS); runtimeResult(host, PermissionSetup.NOTIFICATIONS);
            assertRequest(host, PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
            grant(Manifest.permission.ACTIVITY_RECOGNITION); runtimeResult(host, PermissionSetup.ACTIVITY);
            assertSettings(host, PermissionSetup.LOCATION_SWITCH, Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
            settingsResult(host, PermissionSetup.LOCATION_SWITCH);
            assertSettings(host, PermissionSetup.ALARMS, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
            settingsResult(host, PermissionSetup.ALARMS);
            assertSettings(host, PermissionSetup.BATTERY, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            Shadows.shadowOf(context.getSystemService(PowerManager.class))
                    .setIgnoringBatteryOptimizations(context.getPackageName(), true);
            settingsResult(host, PermissionSetup.BATTERY);
            assertNull(Shadows.shadowOf(host).getNextStartedActivityForResult());
        }
    }

    @Test @Config(sdk = 37, shadows = PromotionManager.class)
    public void android17OpensLiveUpdateSettingsWhenTheOsPreferenceIsDisabled() {
        PromotionManager.allowed = false;
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle();
            assertSettings(host.get(), PermissionSetup.PROMOTION, Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS);
            PromotionManager.allowed = true; settingsResult(host.get(), PermissionSetup.PROMOTION);
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
        }
    }

    @Test @Config(sdk = 37, shadows = PromotionManager.class)
    public void grantedPermissionsDoNotShowAnyDialogOrSettings() {
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle();
            assertNull(Shadows.shadowOf(host.get()).getLastRequestedPermission());
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
        }
    }

    @Test public void permissionDenialAdvancesToTheNextPermissionAndDoesNotLoopOnRelaunch() {
        deny(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); assertRequest(host.get(), PermissionSetup.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS);
            runtimeResult(host.get(), PermissionSetup.NOTIFICATIONS);
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
            runtimeResult(host.get(), PermissionSetup.ACTIVITY);
        }
        RideStore.clear(context); // Detection state clearing must not clear setup decisions.
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle();
            assertNull(Shadows.shadowOf(host.get()).getLastRequestedPermission());
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
        }
    }

    @Test public void deniedLocationNeverRequestsBackgroundButOtherPermissionsStillAppear() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); runtimeResult(host.get(), PermissionSetup.LOCATION);
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
            consumeRuntimeIntent(host.get(), PermissionSetup.ACTIVITY);
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
        }
    }

    @Test public void approximateLocationDoesNotTriggerAnInvalidBackgroundRequest() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); runtimeResult(host.get(), PermissionSetup.LOCATION);
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
            assertFalse(context.getSharedPreferences(PermissionSetup.PREFS, 0)
                    .getBoolean("asked_" + PermissionSetup.BACKGROUND, false));
        }
    }

    @Test public void laterLocationGrantAutomaticallyUnlocksTheBackgroundStep() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); runtimeResult(host.get(), PermissionSetup.LOCATION);
        }
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); assertSettings(host.get(), PermissionSetup.BACKGROUND, Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        }
    }

    @Test public void noSecondRequestStartsWhileADialogIsPending() {
        deny(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); host.get().flow.request(PermissionSetup.ACTIVITY); idle();
            assertRequest(host.get(), PermissionSetup.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS);
            host.get().flow.result(9000); idle();
            assertRequest(host.get(), PermissionSetup.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Test public void pausedActivityDoesNotLaunchTheNextDialogUntilItResumes() {
        deny(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); host.pause(); runtimeResult(host.get(), PermissionSetup.NOTIFICATIONS);
            assertRequest(host.get(), PermissionSetup.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS);
            host.resume(); idle();
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
        }
    }

    @Test public void settingsMustReturnBeforeTheNextPermissionOpens() {
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); assertSettings(host.get(), PermissionSetup.BACKGROUND, Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            host.pause().resume(); idle();
            assertNull(Shadows.shadowOf(host.get()).getLastRequestedPermission());
            settingsResult(host.get(), PermissionSetup.BACKGROUND);
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
        }
    }

    @Test public void rotationKeepsThePendingRequestAndContinuesAfterItsResult() {
        deny(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); host.recreate(); idle();
            assertNull(Shadows.shadowOf(host.get()).getLastRequestedPermission());
            runtimeResult(host.get(), PermissionSetup.NOTIFICATIONS);
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
        }
    }

    @Test public void permanentDenialManualRetryOpensThisAppsSettings() {
        deny(Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); runtimeResult(host.get(), PermissionSetup.ACTIVITY);
            host.get().flow.request(PermissionSetup.ACTIVITY);
            assertSettings(host.get(), PermissionSetup.ACTIVITY, Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        }
    }

    @Test public void unavailableSettingsDoNotCrashOrPreventRemainingRequests() {
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION);
        try (var host = Robolectric.buildActivity(Host.class).create().start()) {
            host.get().unavailable = true; host.resume(); idle();
            assertRequest(host.get(), PermissionSetup.ACTIVITY, Manifest.permission.ACTIVITY_RECOGNITION);
        }
    }

    @Test @Config(sdk = 29)
    public void android10RequestsBackgroundSeparatelyThroughItsNativeDialog() {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); grant(Manifest.permission.ACCESS_FINE_LOCATION); runtimeResult(host.get(), PermissionSetup.LOCATION);
            assertRequest(host.get(), PermissionSetup.BACKGROUND, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            consumeRuntimeIntent(host.get(), PermissionSetup.BACKGROUND);
            assertNull(Shadows.shadowOf(host.get()).getNextStartedActivityForResult());
        }
    }

    @Test @Config(sdk = 32)
    public void olderAndroidOpensNotificationSettingsWhenNotificationsAreBlocked() {
        Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        try (var host = Robolectric.buildActivity(Host.class).setup()) {
            idle(); assertSettings(host.get(), PermissionSetup.NOTIFICATIONS, Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            assertNull(Shadows.shadowOf(host.get()).getLastRequestedPermission());
        }
    }

    private void grant(String... permissions) { Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(permissions); }
    private void deny(String... permissions) { Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(permissions); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void runtimeResult(Activity host, int code) {
        var launch = consumeRuntimeIntent(host, code);
        // Dispatch via the framework, which clears its current-request flag before
        // invoking the callback, just as the real OS permission activity does.
        Shadows.shadowOf(host).receiveResult(launch.intent, Activity.RESULT_CANCELED, null);
        idle();
    }
    private static org.robolectric.shadows.ShadowActivity.IntentForResult consumeRuntimeIntent(Activity host, int code) {
        var launch = Shadows.shadowOf(host).getNextStartedActivityForResult(); assertNotNull(launch);
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", launch.intent.getAction());
        assertEquals(code, launch.requestCode); return launch;
    }
    private static void settingsResult(Activity host, int code) {
        ReflectionHelpers.callInstanceMethod(host,"onActivityResult",
                ReflectionHelpers.ClassParameter.from(int.class,code),
                ReflectionHelpers.ClassParameter.from(int.class,Activity.RESULT_CANCELED),
                ReflectionHelpers.ClassParameter.from(Intent.class,null));
        idle();
    }
    private static void assertRequest(Activity host, int code, String... permissions) {
        var request = Shadows.shadowOf(host).getLastRequestedPermission(); assertNotNull(request);
        assertEquals(code, request.requestCode); assertArrayEquals(permissions, request.requestedPermissions);
    }
    private static void assertSettings(Activity host, int code, String action) {
        var launch = Shadows.shadowOf(host).getNextStartedActivityForResult(); assertNotNull(launch);
        assertEquals(code, launch.requestCode); assertEquals(action, launch.intent.getAction());
        assertEquals(0, launch.intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        if (action.equals(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                || action.equals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)) {
            assertEquals(host.getPackageName(), launch.intent.getStringExtra(Settings.EXTRA_APP_PACKAGE));
        } else if (!action.equals(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) {
            assertEquals("package:" + host.getPackageName(), launch.intent.getDataString());
        }
    }

    public static class Host extends Activity {
        PermissionSetup flow;
        boolean unavailable;
        @Override protected void onCreate(Bundle state) { super.onCreate(state); flow = new PermissionSetup(this, state); }
        @Override protected void onResume() { super.onResume(); flow.resume(); }
        @Override protected void onPause() { flow.pause(); super.onPause(); }
        @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); flow.save(state); }
        @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
            super.onRequestPermissionsResult(code, permissions, results); flow.result(code);
        }
        @Override protected void onActivityResult(int code, int result, Intent data) {
            super.onActivityResult(code, result, data); flow.result(code);
        }
        @Override public void startActivityForResult(Intent intent, int code) {
            if (unavailable) throw new ActivityNotFoundException(); super.startActivityForResult(intent, code);
        }
    }

    // Robolectric 4.17 does not yet shadow the OS user's Live Update preference.
    // This affects tests only; production calls the real SDK 36+ API.
    @Implements(value = NotificationManager.class, minSdk = 36)
    public static class PromotionManager extends ShadowNotificationManager {
        static boolean allowed;
        @Implementation protected boolean canPostPromotedNotifications() { return allowed; }
    }
}
