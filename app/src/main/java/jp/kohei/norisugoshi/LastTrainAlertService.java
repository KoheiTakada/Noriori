package jp.kohei.norisugoshi;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
public final class LastTrainAlertService extends Service {
    static final String CHANNEL="last_train_alert";
    static void channels(Context c){
        NotificationChannel channel=new NotificationChannel(CHANNEL,"終電前の通知",NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null,null);channel.enableVibration(false);c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static PendingIntent open(Context c){return PendingIntent.getActivity(c,4504,new Intent(c,MainActivity.class)
            .putExtra("last_train",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void missedNotice(Context c){
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())||!PermissionUtils.hasNotificationPermission(c))return;channels(c);
        c.getSystemService(NotificationManager.class).notify(4506,new Notification.Builder(c,CHANNEL)
                .setSmallIcon(R.drawable.ic_noriori).setContentTitle("間に合う終電なし").setContentIntent(open(c)).setAutoCancel(true).build());
    }
    static void clearFailureNotice(Context c){
        // Retire an already-posted error from an older version without erasing a real deadline notice.
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(manager!=null)for(android.service.notification.StatusBarNotification item:manager.getActiveNotifications()){
            Notification notification=item.getNotification();
            if(item.getId()==4506&&notification.extras!=null&&android.text.TextUtils.equals("終電を確認できない",
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE)))manager.cancel(4506);
        }
        if(LastTrainStore.prefs(c).contains("errorNotified"))LastTrainStore.prefs(c).edit().remove("errorNotified").apply();
    }
    @Override public int onStartCommand(Intent i,int f,int id){
        NightReset.check(this);
        LastTrainRoute r=LastTrainStore.route(this);
        String zone=LastTrainStore.prefs(this).getString("zone","unknown");
        if(!LastTrainEngine.activeTime(LastTrainEngine.now())||r==null||LastTrainStore.home(this)==null||!LastTrainStore.alerting(this)
                ||LastTrainStore.muted(this,LastTrainEngine.now())||"home".equals(zone)||"travel".equals(zone)){
            LastTrainStore.alerting(this,false);AlarmVibration.refresh(this);stopSelf();return START_NOT_STICKY;
        }
        channels(this);
        Notification n=buildAlarm(r);
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(4500,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(4500,n);
        }catch(RuntimeException e){
            LastTrainStore.alerting(this,false);AlarmVibration.refresh(this);LastTrainStore.problem(this,"通知を開始できない");
            LastTrainStore.prefs(this).edit().remove("announcedDay").commit();stopSelf();return START_NOT_STICKY;
        }
        AlertRepeater.start(this,4500,()->{
            LastTrainRoute current=LastTrainStore.route(this);
            String currentZone=LastTrainStore.prefs(this).getString("zone","");
            return LastTrainStore.alerting(this)&&current!=null&&LastTrainEngine.activeTime(LastTrainEngine.now())
                    &&!LastTrainStore.muted(this,LastTrainEngine.now())&&!"home".equals(currentZone)&&!"travel".equals(currentZone)
                    ?buildAlarm(current):null;
        });
        return START_REDELIVER_INTENT;
    }
    private Notification buildAlarm(LastTrainRoute r){
        String caption=r.station+" · "+r.path;
        if(LastTrainEngine.now()-r.fetchedAt>120_000||!LastTrainCoordinator.matchesLatestPosition(this,r))
            caption+=" · "+LastTrainEngine.time(r.fetchedAt)+"の位置";
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_noriori)
                .setContentTitle(LastTrainEngine.time(r.leaveAt)+"までに出発").setContentText(caption)
                .setStyle(new Notification.BigTextStyle().bigText(caption)).setContentIntent(open(this))
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_close),
                        "停止",LastTrainAlarms.pending(this,"stop",4505)).build())
                .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(false).setCategory(Notification.CATEGORY_ALARM).build();
    }
    @Override public void onDestroy(){AlertRepeater.stop(this,4500);AlarmVibration.refresh(this);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
