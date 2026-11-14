package jp.kohei.norisugoshi;
import android.content.Context;
import android.os.*;
final class AlarmVibration {
    private static boolean running;
    private static long lastPulse;
    static synchronized void refresh(Context c) {
        update(c,false);
    }
    static synchronized void pulse(Context c) {
        update(c,true);
    }
    private static void update(Context c,boolean repeat) {
        boolean wanted=LastTrainStore.alerting(c)||MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(c));
        Vibrator v;
        if(Build.VERSION.SDK_INT>=31){VibratorManager m=c.getSystemService(VibratorManager.class);v=m==null?null:m.getDefaultVibrator();}
        else v=c.getSystemService(Vibrator.class);
        if(v==null||!v.hasVibrator())return;
        long now=SystemClock.elapsedRealtime();
        if(wanted&&(!running||(repeat&&now-lastPulse>=1_100))){
            v.vibrate(VibrationEffect.createOneShot(1_000,VibrationEffect.DEFAULT_AMPLITUDE));lastPulse=now;
        }
        if(!wanted)v.cancel();
        running=wanted;
    }
}
