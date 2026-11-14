package jp.kohei.norisugoshi;
import android.app.*;
import android.content.*;
import android.os.Build;
final class LastTrainAlarms {
    static boolean allowed(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);return a!=null&&(Build.VERSION.SDK_INT<31||a.canScheduleExactAlarms());}
    static PendingIntent pending(Context c,String action,int id){
        return PendingIntent.getBroadcast(c,id,new Intent(c,LastTrainReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void cancel(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);if(a!=null){a.cancel(pending(c,"alarm",4501));a.cancel(pending(c,"refresh",4502));a.cancel(pending(c,"watch",4516));}}
    static void schedule(Context c,LastTrainRoute route){
        cancel(c);
        if(!LastTrainEngine.activeTime(LastTrainEngine.now()))return;
        if(!allowed(c)){LastTrainStore.problem(c,"アラームを許可");return;}
        AlarmManager a=c.getSystemService(AlarmManager.class);
        long now=LastTrainEngine.now(),at=Math.max(now+100,route.alarmAt(LastTrainStore.minutes(c)));
        try{
            a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,"alarm",4501));
            if(at-5*60_000L>now)a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at-5*60_000L,pending(c,"refresh",4502));
            if(PermissionUtils.hasFineLocation(c)&&PermissionUtils.hasBackgroundLocation(c)&&!LastTrainWatchService.running){
                long watchAt=Math.max(now+100,route.leaveAt-LastTrainEngine.WATCH_LEAD);
                a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,watchAt,pending(c,"watch",4516));
            }
        }catch(SecurityException e){cancel(c);LastTrainStore.problem(c,"アラームを許可");}
    }
    static void endWatch(Context c,long at){
        AlarmManager a=c.getSystemService(AlarmManager.class);
        if(a!=null)a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Math.max(LastTrainEngine.now()+100,at),pending(c,"watch_end",4517));
    }
    static void cancelWatchEnd(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);if(a!=null)a.cancel(pending(c,"watch_end",4517));}
    static void cancelWatchStart(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);if(a!=null)a.cancel(pending(c,"watch",4516));}
    static void session(Context c){
        AlarmManager a=c.getSystemService(AlarmManager.class);if(a==null)return;
        long at=LastTrainEngine.nextBoundary(LastTrainEngine.now());PendingIntent pi=pending(c,"session",4521);
        try{if(allowed(c))a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);}
        catch(SecurityException e){a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);}
    }
    static void cancelSession(Context c){AlarmManager a=c.getSystemService(AlarmManager.class);if(a!=null)a.cancel(pending(c,"session",4521));}
}
