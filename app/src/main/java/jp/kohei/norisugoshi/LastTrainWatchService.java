package jp.kohei.norisugoshi;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.google.android.gms.location.*;
public final class LastTrainWatchService extends Service {
    static boolean running;
    private FusedLocationProviderClient client;private LocationCallback callback;
    @Override @SuppressLint("MissingPermission") public void onCreate(){
        super.onCreate();
        if(!LastTrainCoordinator.watchAllowed(this)){stopSelf();return;}
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel ch=new NotificationChannel("last_train_watch","終電の確認",NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null,null);ch.enableVibration(false);ch.setShowBadge(false);nm.createNotificationChannel(ch);
        Notification n=new Notification.Builder(this,"last_train_watch").setSmallIcon(R.drawable.ic_noriori)
                .setContentTitle("終電を確認中").setOngoing(true).setOnlyAlertOnce(true).build();
        try{startForeground(4515,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);}
        catch(RuntimeException e){LastTrainStore.problem(this,"位置情報を確認できない");stopSelf();return;}
        running=true;LastTrainAlarms.cancelWatchStart(this);
        client=LocationServices.getFusedLocationProviderClient(this);
        callback=new LocationCallback(){@Override public void onLocationResult(LocationResult r){
            if(!LastTrainCoordinator.watchAllowed(LastTrainWatchService.this)){stopSelf();return;}
            if(r.getLastLocation()!=null){RideCoordinator.observe(LastTrainWatchService.this,r.getLastLocation());
                LastTrainCoordinator.observe(LastTrainWatchService.this,r.getLastLocation());}}};
        try{client.requestLocationUpdates(new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,60_000)
                .setMinUpdateIntervalMillis(30_000).setMaxUpdateDelayMillis(60_000).build(),callback,Looper.getMainLooper());}
        catch(SecurityException e){stopSelf();}
    }
    @Override public int onStartCommand(Intent i,int f,int id){
        if(!LastTrainCoordinator.watchAllowed(this)){stopSelf();return START_NOT_STICKY;}
        LastTrainAlarms.endWatch(this,LastTrainStore.route(this).leaveAt+1);
        return START_STICKY;
    }
    @Override public void onDestroy(){running=false;if(client!=null&&callback!=null)client.removeLocationUpdates(callback);LastTrainAlarms.cancelWatchEnd(this);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
