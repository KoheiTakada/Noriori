package jp.kohei.norisugoshi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.SystemClock;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RideCoordinator {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"ride-location");t.setDaemon(true);return t;
    });
    private static RideEngine engine;
    private static boolean registered;
    private static long generation;
    static volatile boolean foreground;
    private static boolean probing;
    private RideCoordinator() { }
    static boolean activityPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED;
    }
    static synchronized RideEngine engine(Context c) {
        if (engine==null) engine=RideStore.load(c,SystemClock.elapsedRealtime());
        return engine;
    }
    @SuppressLint("MissingPermission")
    static synchronized void configure(Context context) {
        Context c=context.getApplicationContext();
        if (!PermissionUtils.canProtect(c) || !activityPermission(c)) {
            if (registered) {
                ActivityRecognition.getClient(c).removeActivityTransitionUpdates(motionIntent(c));
                LocationServices.getFusedLocationProviderClient(c).removeLocationUpdates(locationIntent(c));
                registered=false;
            }
            if (!PermissionUtils.canProtect(c)) reset(c);
            return;
        }
        if (registered) return;
        registered=true;final long requestGeneration=++generation;
        List<ActivityTransition> transitions=new ArrayList<>();
        for (int activity:new int[]{DetectedActivity.IN_VEHICLE,DetectedActivity.WALKING,DetectedActivity.RUNNING,
                DetectedActivity.STILL,DetectedActivity.ON_BICYCLE})
            for (int transition:new int[]{ActivityTransition.ACTIVITY_TRANSITION_ENTER,ActivityTransition.ACTIVITY_TRANSITION_EXIT})
                transitions.add(new ActivityTransition.Builder().setActivityType(activity).setActivityTransition(transition).build());
        try {
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(new ActivityTransitionRequest(transitions),motionIntent(c))
                    .addOnFailureListener(error->{synchronized(RideCoordinator.class){
                        if (generation==requestGeneration) registered=false;
                    }DetectionLogStore.append(c,"乗車の検知を登録できません: "+error.getClass().getSimpleName());});
            // Passive never initiates a location fix. It reuses fixes made by the
            // arrival/last-train services and other apps, including after a GPS gap.
            LocationRequest passive=new LocationRequest.Builder(Priority.PRIORITY_PASSIVE,30_000)
                    .setMinUpdateIntervalMillis(10_000).setMaxUpdateDelayMillis(30_000).build();
            LocationServices.getFusedLocationProviderClient(c).requestLocationUpdates(passive,locationIntent(c))
                    .addOnFailureListener(error->DetectionLogStore.append(c,"移動位置の受信を登録できません"));
        } catch (RuntimeException error) {
            registered=false;DetectionLogStore.append(c,"乗車の検知を登録できません: "+error.getClass().getSimpleName());
        }
    }
    static PendingIntent motionIntent(Context c) {
        return PendingIntent.getBroadcast(c,4710,new Intent(c,RideReceiver.class).setAction(RideReceiver.MOTION),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    static PendingIntent locationIntent(Context c) {
        return PendingIntent.getBroadcast(c,4711,new Intent(c,RideReceiver.class).setAction(RideReceiver.LOCATION),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    private static PendingIntent probeIntent(Context c) {
        return PendingIntent.getBroadcast(c,4712,new Intent(c,RideReceiver.class).setAction(RideReceiver.SNAPSHOT),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    @SuppressLint("MissingPermission") static synchronized void probe(Context context) {
        if (!foreground || probing || !PermissionUtils.canProtect(context) || !activityPermission(context)) return;
        Context c=context.getApplicationContext();probing=true;
        try {
            ActivityRecognition.getClient(c).requestActivityUpdates(60_000,probeIntent(c))
                    .addOnFailureListener(error->finishProbe(c));
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->finishProbe(c),120_000);
        } catch (RuntimeException error) { probing=false; }
    }
    static synchronized void finishProbe(Context c) {
        if (!probing) return;probing=false;
        try {ActivityRecognition.getClient(c).removeActivityUpdates(probeIntent(c));}
        catch (SecurityException ignored) { }
        catch (RuntimeException ignored) { }
    }
    static synchronized void motion(Context c,RideEngine.Motion motion,boolean enter,long time) {
        RideEngine e=engine(c);e.motion(motion,enter,time,SystemClock.elapsedRealtime());
        RideStore.save(c,e);update(c);
    }
    static void observe(Context context,Location location) { observe(context,location,null); }
    static void observe(Context context,Location location,Runnable done) {
        Context c=context.getApplicationContext();Location copy=new Location(location);
        final long requestGeneration; synchronized(RideCoordinator.class){requestGeneration=generation;}
        worker.execute(()->{
            try { observeNow(c,copy,requestGeneration); }
            finally { if (done!=null) done.run(); }
        });
    }
    private static void observeNow(Context c,Location l,long requestGeneration) {
        if (!PermissionUtils.canProtect(c)) {RideNotification.hide(c);return;}
        long now=SystemClock.elapsedRealtime();
        long time=l.getElapsedRealtimeNanos()>0?l.getElapsedRealtimeNanos()/1_000_000:
                now-(System.currentTimeMillis()-l.getTime());
        RideEngine.Fix fix=new RideEngine.Fix(l.getLatitude(),l.getLongitude(),l.hasAccuracy()?l.getAccuracy():Float.MAX_VALUE,
                l.hasSpeed()?l.getSpeed():-1,l.hasSpeedAccuracy()?l.getSpeedAccuracyMetersPerSecond():-1,time);
        RailCatalog.Match rail=null;StationCatalog.Entry hub=null;boolean complex=false;
        if (RideEngine.valid(fix,now)) {
            try {
                rail=RailCatalog.get(c).near(fix.latitude,fix.longitude,60+Math.min(25,fix.accuracy));
                hub=HubCatalog.at(c,fix.latitude,fix.longitude,fix.accuracy);
                complex=hub!=null || HubCatalog.nearComplex(c,fix.latitude,fix.longitude);
            } catch (IOException | RuntimeException error) {
                DetectionLogStore.append(c,"駅の位置を照合できません");return;
            }
        }
        synchronized(RideCoordinator.class) {
            if (generation!=requestGeneration) return;
            RideEngine e=engine(c);boolean wasRiding=e.riding;
            e.observe(fix,rail==null?java.util.Collections.emptySet():rail.lines,hub==null?null:hub.name,complex,now);
            if (wasRiding!=e.riding) DetectionLogStore.append(c,e.riding?"線路上の移動から乗車中と判定":"乗車状態を終了");
            RideStore.save(c,e);update(c);
        }
    }
    static synchronized void update(Context c) {
        RideEngine e=engine(c);long now=SystemClock.elapsedRealtime();String hub=e.visibleHub(now);
        if (!PermissionUtils.canProtect(c) || hub==null
                || MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(c)) || LastTrainStore.alerting(c)) {
            RideNotification.hide(c);return;
        }
        RideNotification.channels(c);
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if (manager!=null) {
            try {manager.notify(RideNotification.HUB_ID,RideNotification.station(c,hub,RideEngine.STATION_TTL-(now-e.hubAt)));}
            catch (RuntimeException error) {DetectionLogStore.append(c,"乗換駅の表示を更新できません");}
        }
    }
    static synchronized void dismiss(Context c) {
        RideEngine e=engine(c);e.dismiss(SystemClock.elapsedRealtime());RideStore.save(c,e);RideNotification.hide(c);
    }
    static synchronized void reset(Context c) {
        generation++;engine=null;RideStore.clear(c);RideNotification.hide(c);
        c.stopService(new Intent(c,RideTrackingService.class));
    }
    static synchronized boolean shouldTrack(Context c) {return engine(c).shouldTrack(SystemClock.elapsedRealtime());}
    static synchronized boolean balanced(Context c) {
        RideEngine e=engine(c);
        return !e.riding && e.started>=0 && SystemClock.elapsedRealtime()-e.started>3*60_000;
    }
    // Test boundary; production never clears inferred state simply by opening the app.
    static synchronized void resetForTest() {engine=null;registered=false;foreground=false;probing=false;generation++;}
    static void awaitIdleForTest() throws InterruptedException {
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        worker.execute(done::countDown);
        if (!done.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("ride worker did not finish");
    }
}
