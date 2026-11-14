package jp.kohei.norisugoshi;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/** One native permission or settings screen at a time, including after an upgrade. */
final class PermissionSetup {
    static final String PREFS = "permission_setup_v1";
    static final int LOCATION = 1001;
    static final int BACKGROUND = 1002;
    static final int NOTIFICATIONS = 1003;
    static final int ACTIVITY = 1004;
    static final int LOCATION_SWITCH = 1005;
    static final int BATTERY = 1006;
    static final int ALARMS = 1007;
    static final int PROMOTION = 1008;
    private static final int[] STEPS = {
            LOCATION, BACKGROUND, NOTIFICATIONS, ACTIVITY, LOCATION_SWITCH,
            ALARMS, PROMOTION, BATTERY
    };
    private static final String PENDING = "permissionSetupPending";

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::advance;
    private boolean resumed;
    private int pending;

    PermissionSetup(Activity activity, Bundle savedState) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        pending = savedState == null ? 0 : savedState.getInt(PENDING, 0);
    }

    void resume() { resumed = true; schedule(); }
    void pause() { resumed = false; handler.removeCallbacks(advance); }
    void save(Bundle state) { state.putInt(PENDING, pending); }

    void result(int requestCode) {
        if (pending != requestCode) return;
        pending = 0;
        schedule();
    }

    private void schedule() {
        handler.removeCallbacks(advance);
        if (resumed && pending == 0) handler.post(advance);
    }

    private void advance() {
        if (!resumed || pending != 0 || activity.isFinishing() || activity.isDestroyed()) return;
        for (int step : STEPS) {
            if (prefs.getBoolean(key(step), false)) continue;
            if (!needed(step)) continue;
            // Never ask for background access before precise foreground access.
            if (step == BACKGROUND && !PermissionUtils.hasFineLocation(activity)) continue;
            request(step);
            return;
        }
    }

    void request(int step) {
        if (pending != 0 || !needed(step)) return;
        if (step == BACKGROUND && !PermissionUtils.hasFineLocation(activity)) {
            request(LOCATION);
            return;
        }
        boolean previouslyAsked = prefs.getBoolean(key(step), false);
        mark(step);
        pending = step;
        try {
            switch (step) {
                case LOCATION:
                    runtime(step, Manifest.permission.ACCESS_FINE_LOCATION, previouslyAsked,
                            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
                    break;
                case BACKGROUND:
                    if (Build.VERSION.SDK_INT == 29) {
                        runtime(step, Manifest.permission.ACCESS_BACKGROUND_LOCATION, previouslyAsked,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                    } else {
                        Toast.makeText(activity, "権限 → 位置情報 → 常に許可", Toast.LENGTH_LONG).show();
                        settings(step, appSettings());
                    }
                    break;
                case NOTIFICATIONS:
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        runtime(step, Manifest.permission.POST_NOTIFICATIONS, previouslyAsked,
                                Manifest.permission.POST_NOTIFICATIONS);
                    } else {
                        settings(step, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                    }
                    break;
                case ACTIVITY:
                    runtime(step, Manifest.permission.ACTIVITY_RECOGNITION, previouslyAsked,
                            Manifest.permission.ACTIVITY_RECOGNITION);
                    break;
                case LOCATION_SWITCH:
                    settings(step, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    break;
                case ALARMS:
                    settings(step, new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()));
                    break;
                case PROMOTION:
                    settings(step, new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                    break;
                case BATTERY:
                    settings(step, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()));
                    break;
                default:
                    pending = 0;
                    schedule();
            }
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            pending = 0;
            Toast.makeText(activity, "設定を開けない", Toast.LENGTH_SHORT).show();
            schedule();
        }
    }

    private void runtime(int step, String permission, boolean previouslyAsked, String... permissions) {
        // A permanently denied runtime request silently returns without a dialog.
        // A user-initiated retry should still provide a way to grant it.
        if (previouslyAsked && !activity.shouldShowRequestPermissionRationale(permission)) {
            settings(step, appSettings());
        } else {
            activity.requestPermissions(permissions, step);
        }
    }

    private void settings(int step, Intent intent) {
        // No NEW_TASK: wait for this specific screen to return before advancing.
        activity.startActivityForResult(intent, step);
    }

    private boolean needed(int step) {
        switch (step) {
            case LOCATION: return !PermissionUtils.hasFineLocation(activity);
            case BACKGROUND: return !PermissionUtils.hasBackgroundLocation(activity);
            case NOTIFICATIONS: return !PermissionUtils.hasNotificationPermission(activity);
            case ACTIVITY: return !RideCoordinator.activityPermission(activity);
            case LOCATION_SWITCH: return !PermissionUtils.isLocationEnabled(activity);
            case BATTERY: return !PermissionUtils.isIgnoringBatteryOptimizations(activity);
            case ALARMS: return Build.VERSION.SDK_INT >= 31 && !LastTrainAlarms.allowed(activity);
            case PROMOTION: return !PermissionUtils.canPostPromotedNotifications(activity);
            default: return false;
        }
    }

    private Uri packageUri() { return Uri.parse("package:" + activity.getPackageName()); }
    private Intent appSettings() { return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()); }
    private static String key(int step) { return "asked_" + step; }
    private void mark(int step) { prefs.edit().putBoolean(key(step), true).commit(); }
}
