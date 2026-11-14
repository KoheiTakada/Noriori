package jp.kohei.norisugoshi;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** One timer and one notification slot per active alarm. */
final class AlertRepeater {
    static final long INTERVAL=1_200;
    private static final long WAKE_LEASE=10*60_000L;
    private static final Map<Integer,AlertRepeater> active=new HashMap<>();
    private final Context context;
    private final Context owner;
    private final int id;
    private final Supplier<Notification> notification;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=this::repeat;
    private PowerManager.WakeLock wake;
    private long wakeUntil;
    private boolean stopped;
    private AlertRepeater(Context c,int id,Supplier<Notification> notification){
        context=c.getApplicationContext();owner=c;this.id=id;this.notification=notification;
    }
    static synchronized void start(Context c,int id,Supplier<Notification> notification){
        AlertRepeater previous=active.get(id);
        if(previous!=null&&previous.owner==c)return;
        stop(id);
        AlertRepeater repeater=new AlertRepeater(c,id,notification);active.put(id,repeater);
        Notification first=notification.get();
        if(first==null||!repeater.canPost(first)){repeater.cancelAlarm();return;}
        repeater.keepAwake();AlarmVibration.pulse(c);repeater.handler.postDelayed(repeater.tick,INTERVAL);
    }
    static synchronized void stop(int id){
        AlertRepeater repeater=active.remove(id);if(repeater==null)return;
        repeater.stopped=true;repeater.handler.removeCallbacks(repeater.tick);
        if(repeater.wake!=null&&repeater.wake.isHeld())repeater.wake.release();
    }
    static synchronized void stop(Context owner,int id){
        AlertRepeater current=active.get(id);if(current!=null&&current.owner==owner)stop(id);
    }
    static synchronized boolean isRunning(int id){return active.containsKey(id);}
    private void keepAwake(){
        if(wake==null){
            PowerManager manager=context.getSystemService(PowerManager.class);
            if(manager==null)return;
            wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Noriori:alarm-repeat");wake.setReferenceCounted(false);
        }
        if(!wake.isHeld()||SystemClock.elapsedRealtime()>=wakeUntil-30_000){
            wake.acquire(WAKE_LEASE);wakeUntil=SystemClock.elapsedRealtime()+WAKE_LEASE;
        }
    }
    private void repeat(){
        if(stopped)return;
        NightReset.check(context);if(stopped)return;
        Notification next=notification.get();
        synchronized(AlertRepeater.class){
            if(stopped||active.get(id)!=this)return;
            if(next==null){stop(id);AlarmVibration.refresh(context);return;}
            if(!canPost(next)){cancelAlarm();return;}
            try{context.getSystemService(NotificationManager.class).notify(id,next);}
            catch(RuntimeException e){cancelAlarm();return;}
            AlarmVibration.pulse(context);keepAwake();handler.postDelayed(tick,INTERVAL);
        }
    }
    private boolean canPost(Notification notification){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null||!PermissionUtils.hasNotificationPermission(context)||!manager.areNotificationsEnabled())return false;
        android.app.NotificationChannel channel=manager.getNotificationChannel(notification.getChannelId());
        return channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    private void cancelAlarm(){
        stop(id);
        if(id==4500)LastTrainCoordinator.stopActive(context,false);else TravelMonitorService.cancelCurrent(context);
    }
}
