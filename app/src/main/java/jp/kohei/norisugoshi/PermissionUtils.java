package jp.kohei.norisugoshi;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

public final class PermissionUtils {
    private PermissionUtils() {
    }

    public static boolean hasFineLocation(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasBackgroundLocation(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager == null || manager.areNotificationsEnabled();
    }

    public static boolean canPostPromotedNotifications(Context context) {
        if (Build.VERSION.SDK_INT < 36) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canPostPromotedNotifications();
    }

    public static boolean isLocationEnabled(Context context) {
        LocationManager manager = context.getSystemService(LocationManager.class);
        return manager != null && manager.isLocationEnabled();
    }

    public static boolean canProtect(Context context) {
        return hasFineLocation(context)
                && hasBackgroundLocation(context)
                && hasNotificationPermission(context)
                && isLocationEnabled(context);
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager manager = context.getSystemService(PowerManager.class);
        return manager != null
                && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static void openAppSettings(Context context) {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openLocationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openBatteryOptimizationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
