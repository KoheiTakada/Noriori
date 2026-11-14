package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class RideStore {
    static final String PREFS="ride_context_v1";
    static RideEngine load(Context c,long now) {
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        RideEngine e=new RideEngine();
        long boot=System.currentTimeMillis()-SystemClock.elapsedRealtime();
        if (Math.abs(boot-p.getLong("boot",Long.MIN_VALUE/2))>10_000) return e;
        e.started=p.getLong("started",-1);e.lastRailMotion=p.getLong("rail",-1);
        e.lastFix=p.getLong("fix",-1);e.lastEvent=p.getLong("event",-1);
        e.walkingSince=p.getLong("walk",-1);e.stillSince=p.getLong("still",-1);
        e.vehicle=p.getBoolean("vehicle",false);e.riding=p.getBoolean("riding",false);
        e.suppressed=p.getBoolean("suppressed",false);
        e.dismissedAt=p.getLong("dismissedAt",-1);
        e.bicycle=p.getBoolean("bicycle",false);
        // Never restore a station name; only a fresh, confirmed pair of fixes may show it.
        e.tick(now);return e;
    }
    static void save(Context c,RideEngine e) {
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putLong("boot",System.currentTimeMillis()-SystemClock.elapsedRealtime())
                .putLong("started",e.started).putLong("rail",e.lastRailMotion)
                .putLong("fix",e.lastFix).putLong("event",e.lastEvent)
                .putLong("walk",e.walkingSince).putLong("still",e.stillSince)
                .putBoolean("vehicle",e.vehicle).putBoolean("riding",e.riding)
                .putBoolean("bicycle",e.bicycle)
                .putBoolean("suppressed",e.suppressed).putLong("dismissedAt",e.dismissedAt).apply();
    }
    static void clear(Context c) {c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();}
}
