package jp.kohei.norisugoshi;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public final class RideTrackingService extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient client;
    private boolean requested,balanced;
    private final LocationCallback locations=new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) {
            if (result.getLastLocation()!=null) RideCoordinator.observe(RideTrackingService.this,result.getLastLocation());
        }
    };
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            RideCoordinator.update(RideTrackingService.this);
            if (!PermissionUtils.canProtect(RideTrackingService.this)
                    || !RideCoordinator.activityPermission(RideTrackingService.this)
                    || !RideCoordinator.shouldTrack(RideTrackingService.this)) {stopSelf();return;}
            boolean nextBalanced=RideCoordinator.balanced(RideTrackingService.this);
            if (nextBalanced!=balanced) request(nextBalanced);
            handler.postDelayed(this,15_000);
        }
    };
    @Override public void onCreate() {super.onCreate();client=LocationServices.getFusedLocationProviderClient(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (!PermissionUtils.canProtect(this) || !RideCoordinator.activityPermission(this) || !RideCoordinator.shouldTrack(this)) {
            stopSelf();return START_NOT_STICKY;
        }
        RideNotification.channels(this);
        try {startForeground(RideNotification.TRACK_ID,RideNotification.tracking(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);}
        catch (RuntimeException error) {stopSelf();return START_NOT_STICKY;}
        if (!requested) request(RideCoordinator.balanced(this));
        handler.removeCallbacks(tick);handler.postDelayed(tick,15_000);
        return START_STICKY;
    }
    @SuppressLint("MissingPermission") private void request(boolean powerSaving) {
        balanced=powerSaving;
        if (requested) client.removeLocationUpdates(locations);
        requested=true;
        LocationRequest request=new LocationRequest.Builder(powerSaving?Priority.PRIORITY_BALANCED_POWER_ACCURACY:Priority.PRIORITY_HIGH_ACCURACY,
                powerSaving?60_000:15_000).setMinUpdateIntervalMillis(powerSaving?30_000:5_000)
                .setMaxUpdateDelayMillis(powerSaving?60_000:15_000).setWaitForAccurateLocation(false).build();
        try {client.requestLocationUpdates(request,locations,Looper.getMainLooper())
                .addOnFailureListener(error->{DetectionLogStore.append(this,"乗車位置の取得を開始できません");stopSelf();});}
        catch (RuntimeException error) {DetectionLogStore.append(this,"乗車位置の取得を開始できません");stopSelf();}
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (client!=null && requested) client.removeLocationUpdates(locations);
        requested=false;RideNotification.hide(this);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
    static void start(Context c) {
        try {c.startForegroundService(new Intent(c,RideTrackingService.class));}
        catch (RuntimeException error) {DetectionLogStore.append(c,"乗車位置の取得を開始できません: "+error.getClass().getSimpleName());}
    }
}
