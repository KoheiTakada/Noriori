package jp.kohei.norisugoshi;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Clears waiting/alert states once at 04:00 JST; saved places keep working. */
final class NightReset {
    static final String ACTION="night_reset";
    private static final ZoneId TOKYO=ZoneId.of("Asia/Tokyo");
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("night_reset_v1",0);}
    static long next(long now){return Instant.ofEpochMilli(now).atZone(TOKYO).minusHours(4).toLocalDate()
            .plusDays(1).atTime(4,0).atZone(TOKYO).toInstant().toEpochMilli();}
    static long lastResetAt(Context c){return prefs(c).getLong("at",0);}
    static synchronized boolean check(Context c){
        long now=LastTrainEngine.now();
        String day=Instant.ofEpochMilli(now).atZone(TOKYO).minusHours(4).toLocalDate().toString();
        String previous=prefs(c).getString("day","");
        if(day.equals(previous))return false;
        // Installing/updating during a trip does not switch off that trip. A backward clock change does not reset it either.
        if(previous.isEmpty()||previous.compareTo(day)>0){prefs(c).edit().putString("day",day).remove("at").commit();return false;}
        long boundary=LocalDate.parse(day).atTime(4,0).atZone(TOKYO).toInstant().toEpochMilli();
        prefs(c).edit().putString("day",day).putLong("at",boundary).commit();
        MonitorStateStore.disarmAll(c);
        LastTrainStore.prefs(c).edit().putBoolean("alerting",false).putLong("generation",LastTrainStore.generation(c)+1)
                .remove("route").remove("zone").remove("retryAt").remove("announcedDay").remove("mutedDay").remove("errorNotified").commit();
        LastTrainCoordinator.resetNight(c);
        TravelMonitorService.cancelCurrent(c);RideCoordinator.reset(c);
        NotificationManager manager=c.getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(4506);
        AlarmVibration.refresh(c);DetectionLogStore.append(c,"4時: 通知待ちと警報をすべてオフ");schedule(c);return true;
    }
    static void configure(Context c){
        if(!check(c))schedule(c);
    }
    private static void schedule(Context c){
        AlarmManager manager=c.getSystemService(AlarmManager.class);if(manager==null)return;
        long at=next(LastTrainEngine.now());android.app.PendingIntent pending=LastTrainAlarms.pending(c,ACTION,4530);
        try{
            if(Build.VERSION.SDK_INT<31||manager.canScheduleExactAlarms())manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);
        }catch(SecurityException e){manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending);}
    }
}
