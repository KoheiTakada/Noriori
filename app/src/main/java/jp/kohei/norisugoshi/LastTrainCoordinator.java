package jp.kohei.norisugoshi;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.location.Location;
import android.os.*;
import com.google.android.gms.location.*;
import com.google.android.gms.tasks.CancellationTokenSource;
import java.util.*;
import java.util.concurrent.*;

final class LastTrainCoordinator {
    private static long registered=-1;
    private static CancellationTokenSource currentFix;
    static void configure(Context context){
        Context c=context.getApplicationContext();
        NightReset.configure(c);
        LastTrainAlertService.clearFailureNotice(c);
        HomeStation home=LastTrainStore.home(c);
        if(home==null){LastTrainAlarms.cancelSession(c);quiet(c);return;}
        LastTrainAlarms.session(c);
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())){quiet(c);return;}
        restore(c);
        if(!PermissionUtils.hasFineLocation(c)||!PermissionUtils.hasBackgroundLocation(c)){stopWatch(c);return;}
        register(c);
        requestFix(c);
    }
    static PendingIntent events(Context c,String action,int id){
        return PendingIntent.getBroadcast(c,id,new Intent(c,LastTrainReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    }
    @SuppressLint("MissingPermission")
    private static void register(Context c){
        long g=LastTrainStore.generation(c);if(registered==g)return;registered=g;
        LastTrainStore.prefs(c).edit().putBoolean("tracking",true).apply();
        List<Geofence> regions=new ArrayList<>();
        List<ReturnArea> areas=Arrays.asList(LastTrainStore.notificationArea(c),LastTrainStore.quietArea(c));
        for(ReturnArea a:areas)regions.add(new Geofence.Builder().setRequestId("return:"+a.id)
                .setCircularRegion(a.latitude,a.longitude,(float)a.radius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER|Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(30_000).setExpirationDuration(Geofence.NEVER_EXPIRE).build());
        GeofencingClient geo=LocationServices.getGeofencingClient(c);
        PendingIntent pi=events(c,"area",4510);
        geo.removeGeofences(pi).addOnCompleteListener(unused->{
            if(g!=LastTrainStore.generation(c)||registered!=g||!LastTrainEngine.activeTime(LastTrainEngine.now()))return;
            geo.addGeofences(new GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER|GeofencingRequest.INITIAL_TRIGGER_EXIT)
                    .addGeofences(regions).build(),pi).addOnFailureListener(e->{registered=-1;LastTrainStore.problem(c,"位置情報を確認できない");});
        });
        try{LocationServices.getFusedLocationProviderClient(c).requestLocationUpdates(
                new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,300_000)
                        .setMinUpdateIntervalMillis(60_000).setMaxUpdateDelayMillis(300_000).build(),events(c,"location",4511));}
        catch(SecurityException e){LastTrainStore.problem(c,"位置情報を許可");}
    }
    private static void unregister(Context c){
        if(registered==-1&&!LastTrainStore.prefs(c).getBoolean("tracking",false)&&!LastTrainStore.prefs(c).contains("fixAt"))return;
        registered=-1;long generation=LastTrainStore.generation(c);
        LastTrainStore.prefs(c).edit().putBoolean("tracking",true).apply();
        try{
            com.google.android.gms.tasks.Tasks.whenAllSuccess(
                    LocationServices.getGeofencingClient(c).removeGeofences(events(c,"area",4510)),
                    LocationServices.getFusedLocationProviderClient(c).removeLocationUpdates(events(c,"location",4511)))
                    .addOnSuccessListener(unused->{
                        if(generation==LastTrainStore.generation(c)&&(LastTrainStore.home(c)==null||!LastTrainEngine.activeTime(LastTrainEngine.now())))
                            LastTrainStore.prefs(c).edit().remove("tracking").remove("fixAt").remove("fixLat").remove("fixLon").apply();
                    }).addOnFailureListener(e->DetectionLogStore.append(c,"終電の位置更新を解除できない。次回再試行"));
        }catch(RuntimeException e){DetectionLogStore.append(c,"終電の位置更新の解除待ち");}
    }
    @SuppressLint("MissingPermission")
    static void requestFix(Context context){
        Context c=context.getApplicationContext();
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())||LastTrainStore.home(c)==null||!PermissionUtils.hasFineLocation(c))return;
        if(currentFix!=null)currentFix.cancel();CancellationTokenSource source=new CancellationTokenSource();currentFix=source;
        try{LocationServices.getFusedLocationProviderClient(c).getCurrentLocation(
                new CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdateAgeMillis(120_000).setDurationMillis(15_000).build(),
                source.getToken()).addOnSuccessListener(fix->{
                    if(source.getToken().isCancellationRequested()||!LastTrainEngine.activeTime(LastTrainEngine.now()))return;
                    if(fix!=null)observe(c,fix);else if(LastTrainStore.route(c)==null)LastTrainStore.problem(c,"現在地を確認できない");
                }).addOnFailureListener(e->{if(!source.getToken().isCancellationRequested()&&LastTrainEngine.activeTime(LastTrainEngine.now())&&LastTrainStore.route(c)==null)LastTrainStore.problem(c,"現在地を確認できない");});}
        catch(SecurityException e){LastTrainStore.problem(c,"位置情報を許可");}
    }
    static synchronized void observe(Context context,Location location){
        Context c=context.getApplicationContext();long now=LastTrainEngine.now();
        if(!LastTrainEngine.activeTime(now))return;
        if(!location.hasAccuracy()||!Float.isFinite(location.getAccuracy())||location.getAccuracy()<0
                ||!Double.isFinite(location.getLatitude())||Math.abs(location.getLatitude())>85
                ||!Double.isFinite(location.getLongitude())||Math.abs(location.getLongitude())>180
                ||location.getTime()<=0||now-location.getTime()>120_000||location.getTime()>now+10_000)return;
        HomeStation home=LastTrainStore.home(c);if(home==null)return;
        String before=LastTrainStore.prefs(c).getString("zone","unknown");
        String zone=LastTrainEngine.zone(location.getLatitude(),location.getLongitude(),location.getAccuracy(),LastTrainStore.quietArea(c),LastTrainStore.notificationArea(c),before);
        android.content.SharedPreferences.Editor edit=LastTrainStore.prefs(c).edit().putString("zone",zone)
                .putString("fixLat",Double.toString(location.getLatitude())).putString("fixLon",Double.toString(location.getLongitude())).putLong("fixAt",location.getTime());
        if("home".equals(zone)&&!"home".equals(before))edit.remove("mutedDay").remove("announcedDay");
        if("travel".equals(zone)&&!"travel".equals(before)&&!LastTrainStore.muted(c,now))edit.remove("announcedDay");
        if(!zone.equals(before))edit.putLong("generation",LastTrainStore.generation(c)+1);
        edit.commit();
        if(!"notify".equals(zone)){
            if("home".equals(zone)||"travel".equals(zone)){LastTrainAlarms.cancel(c);stopActive(c,false);stopWatch(c);LastTrainSearchJob.cancel(c);}
            return;
        }
        if(LastTrainStore.muted(c,now)){LastTrainAlarms.cancel(c);stopWatch(c);return;}
        reconcileWatch(c);
        LastTrainRoute cached=LastTrainStore.route(c);
        if(LastTrainEngine.reusable(cached,location.getLatitude(),location.getLongitude(),now)){evaluate(c);return;}
        if(LastTrainStore.prefs(c).getLong("retryAt",0)>now){restore(c);return;}
        LastTrainSearchJob.enqueue(c);
    }
    static synchronized void completeSearch(Context c,long generation,LastTrainRoute result,Exception error){
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())||generation!=LastTrainStore.generation(c)||LastTrainStore.home(c)==null
                ||!"notify".equals(LastTrainStore.prefs(c).getString("zone",""))||LastTrainStore.muted(c,LastTrainEngine.now()))return;
        if(error==null&&result!=null){
            if(!result.day.equals(LastTrainEngine.day(LastTrainEngine.now()))||!matchesLatestPosition(c,result))return;
            LastTrainStore.prefs(c).edit().remove("retryAt").remove("errorNotified").commit();
            NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null)nm.cancel(4506);
            LastTrainStore.route(c,result);evaluate(c);return;
        }
        LastTrainStore.prefs(c).edit().putLong("retryAt",LastTrainEngine.now()+10*60_000L).commit();
        LastTrainStore.problem(c,error instanceof TransitLastTrainClient.DataUnavailable?error.getMessage():"終電を確認できない");
        if(error!=null)DetectionLogStore.append(c,"終電取得失敗: "+error.getClass().getSimpleName());
        restore(c);
    }
    static boolean matchesLatestPosition(Context c,LastTrainRoute r){
        try{
            double lat=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLat",Double.toString(r.originLat)));
            double lon=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLon",Double.toString(r.originLon)));
            return Geo.distanceMeters(lat,lon,r.originLat,r.originLon)<200;
        }catch(NumberFormatException e){return false;}
    }
    static synchronized void restore(Context c){
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())){if(LastTrainStore.home(c)!=null)LastTrainAlarms.session(c);quiet(c);return;}
        String zone=LastTrainStore.prefs(c).getString("zone","unknown");
        if(LastTrainStore.home(c)==null||"home".equals(zone)||"travel".equals(zone)||LastTrainStore.muted(c,LastTrainEngine.now())){
            LastTrainAlarms.cancel(c);stopActive(c,false);stopWatch(c);LastTrainSearchJob.cancel(c);return;
        }
        reconcileWatch(c);
        if(LastTrainStore.alerting(c)&&LastTrainStore.route(c)!=null){
            try{c.startForegroundService(new Intent(c,LastTrainAlertService.class));}
            catch(RuntimeException e){LastTrainStore.problem(c,"通知を復元できない");}
        }else{
            if(LastTrainStore.route(c)==null&&LastTrainStore.alerting(c))stopActive(c,false);
            evaluate(c);
        }
    }
    static synchronized void evaluate(Context c){
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())){if(LastTrainStore.home(c)!=null)LastTrainAlarms.session(c);quiet(c);return;}
        LastTrainRoute r=LastTrainStore.route(c);
        reconcileWatch(c);
        LastTrainEngine.Decision d=LastTrainEngine.decide(r,LastTrainStore.minutes(c),LastTrainEngine.now(),
                LastTrainStore.muted(c,LastTrainEngine.now()),r!=null&&LastTrainStore.announced(c,r),
                LastTrainStore.prefs(c).getString("zone","unknown"));
        if(d==LastTrainEngine.Decision.SCHEDULE)LastTrainAlarms.schedule(c,r);
        else if(d==LastTrainEngine.Decision.ALERT&&PermissionUtils.hasNotificationPermission(c)){
            LastTrainStore.markAnnounced(c,r);LastTrainStore.alerting(c,true);
            try{c.startForegroundService(new Intent(c,LastTrainAlertService.class));}
            catch(RuntimeException e){LastTrainStore.alerting(c,false);LastTrainStore.prefs(c).edit().remove("announcedDay").commit();LastTrainStore.problem(c,"通知を開始できない");}
        }else if(d==LastTrainEngine.Decision.MISSED){
            LastTrainAlarms.cancel(c);
            if(!matchesLatestPosition(c,r))LastTrainStore.problem(c,"終電を確認できない");
            else{LastTrainStore.markAnnounced(c,r);LastTrainStore.problem(c,"間に合う終電なし");LastTrainAlertService.missedNotice(c);}
        }else if(d==LastTrainEngine.Decision.NONE&&!LastTrainStore.alerting(c))LastTrainAlarms.cancel(c);
    }
    static void stopActive(Context c,boolean mute){
        AlertRepeater.stop(4500);
        if(mute)LastTrainStore.mute(c,LastTrainEngine.now());else LastTrainStore.alerting(c,false);
        c.stopService(new Intent(c,LastTrainAlertService.class));
        NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null){nm.cancel(4500);nm.cancel(4506);}
        AlarmVibration.refresh(c);
        if(mute){LastTrainAlarms.cancel(c);stopWatch(c);LastTrainSearchJob.cancel(c);}
    }
    static boolean watchAllowed(Context c){
        return PermissionUtils.hasFineLocation(c)&&PermissionUtils.hasBackgroundLocation(c)&&LastTrainSearchJob.eligible(c)
                &&LastTrainEngine.watchNeeded(LastTrainStore.route(c),LastTrainEngine.now());
    }
    private static void reconcileWatch(Context c){
        if(!watchAllowed(c)){stopWatch(c);return;}
        try{c.startForegroundService(new Intent(c,LastTrainWatchService.class));}
        catch(RuntimeException e){DetectionLogStore.append(c,"終電の位置更新: 背景での開始待ち");}
    }
    static void stopWatch(Context c){
        c.stopService(new Intent(c,LastTrainWatchService.class));LastTrainAlarms.cancelWatchEnd(c);
        NotificationManager nm=c.getSystemService(NotificationManager.class);if(nm!=null)nm.cancel(4515);
    }
    static void resetNight(Context c){
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())){
            quiet(c);if(LastTrainStore.home(c)!=null)LastTrainAlarms.session(c);else LastTrainAlarms.cancelSession(c);
        }else{
            if(currentFix!=null){currentFix.cancel();currentFix=null;}
            LastTrainAlarms.cancel(c);stopActive(c,false);stopWatch(c);LastTrainSearchJob.cancel(c);
        }
    }
    private static void quiet(Context c){
        if(currentFix!=null){currentFix.cancel();currentFix=null;}
        LastTrainAlarms.cancel(c);stopActive(c,false);stopWatch(c);LastTrainSearchJob.cancel(c);unregister(c);
    }
}
