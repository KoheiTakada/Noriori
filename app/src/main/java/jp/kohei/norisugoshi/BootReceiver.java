package jp.kohei.norisugoshi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        DetectionLogStore.append(context, "端末起動またはアプリ更新: 駅の見守りを復元");
        GeofenceRegistrar.register(context);
        LastTrainCoordinator.configure(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) RideCoordinator.reset(context);
        RideCoordinator.configure(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && RideCoordinator.activityPermission(context) && RideCoordinator.shouldTrack(context)) RideTrackingService.start(context);
    }
}
