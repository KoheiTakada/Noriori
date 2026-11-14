package jp.kohei.norisugoshi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

final class RideNotification {
    static final int TRACK_ID=4701,HUB_ID=4702;
    static final String TRACK_CHANNEL="ride_tracking_v1",HUB_CHANNEL="ride_station_v1";
    // Public EXTRA_REQUEST_PROMOTED_ONGOING. The native setter was added in
    // 36.1; the documented extra also allows a compileSdk 36.0 build to request it.
    static final String PROMOTED_EXTRA="android.requestPromotedOngoing";
    static void channels(Context c) {
        NotificationManager m=c.getSystemService(NotificationManager.class);
        if (m==null) return;
        NotificationChannel tracking=new NotificationChannel(TRACK_CHANNEL,"乗車の検知",NotificationManager.IMPORTANCE_LOW);
        tracking.setSound(null,null);tracking.enableVibration(false);tracking.setShowBadge(false);
        NotificationChannel hub=new NotificationChannel(HUB_CHANNEL,"乗換駅",NotificationManager.IMPORTANCE_LOW);
        hub.setSound(null,null);hub.enableVibration(false);hub.setShowBadge(false);
        m.createNotificationChannel(tracking);m.createNotificationChannel(hub);
    }
    static Notification tracking(Context c) {
        return new Notification.Builder(c,TRACK_CHANNEL).setSmallIcon(R.drawable.ic_noriori)
                .setContentTitle("移動中").setContentIntent(open(c)).setOngoing(true)
                .setOnlyAlertOnce(true).setShowWhen(false).build();
    }
    static Notification station(Context c,String name,long remaining) {
        Notification.Builder b=new Notification.Builder(c,HUB_CHANNEL)
                .setSmallIcon(R.drawable.ic_noriori).setContentTitle(name)
                .setContentIntent(open(c)).setOngoing(true).setOnlyAlertOnce(true)
                .setShowWhen(false).setTimeoutAfter(Math.max(1,remaining))
                .setCategory(Notification.CATEGORY_NAVIGATION).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(dismiss(c)).addAction(new Notification.Action.Builder(null,"非表示",dismiss(c)).build());
        if (Build.VERSION.SDK_INT>=36) {
            Bundle extras=new Bundle();extras.putBoolean(PROMOTED_EXTRA,true);b.addExtras(extras);
            b.setShortCriticalText(shortName(name));
        }
        return b.build();
    }
    static String shortName(String station) {
        String name=station.endsWith("駅")?station.substring(0,station.length()-1):station;
        // Keep the real name. Android may choose icon-only if it cannot fit it.
        return name;
    }
    static void hide(Context c) {
        NotificationManager m=c.getSystemService(NotificationManager.class);
        if (m!=null) m.cancel(HUB_ID);
    }
    private static PendingIntent open(Context c) {
        return PendingIntent.getActivity(c,4700,new Intent(c,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent dismiss(Context c) {
        return PendingIntent.getBroadcast(c,4702,new Intent(c,RideReceiver.class).setAction(RideReceiver.DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
}
