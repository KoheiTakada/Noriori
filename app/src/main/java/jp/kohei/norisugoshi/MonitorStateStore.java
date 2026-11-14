package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.SharedPreferences;

public final class MonitorStateStore {
    public static final String MODE_NONE = "none";
    public static final String MODE_MONITORING = "monitoring";
    public static final String MODE_ALERTING = "alerting";

    private static final String PREFS = "monitor_state";
    private static final String KEY_ACTIVE_STATION = "active_station";
    private static final String KEY_ACTIVE_MODE = "active_mode";
    private static final String KEY_ACTIVE_SEEN_OUTSIDE = "active_seen_outside";
    private static final String KEY_STATE_SCHEMA = "state_schema";
    private static final int CURRENT_STATE_SCHEMA = 5;

    private MonitorStateStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isArmed(Context context, Station station) {
        return prefs(context).getBoolean("armed_" + station.id, false);
    }

    public static void setArmed(Context context, Station station, boolean armed) {
        if(armed)NightReset.check(context);
        prefs(context).edit().putBoolean("armed_" + station.id, armed).apply();
    }

    static void disarmAll(Context context){
        SharedPreferences preferences=prefs(context);SharedPreferences.Editor edit=preferences.edit();
        for(String key:preferences.getAll().keySet())if(key.startsWith("armed_"))edit.putBoolean(key,false);
        edit.putString(KEY_ACTIVE_MODE,MODE_NONE).remove(KEY_ACTIVE_STATION).remove(KEY_ACTIVE_SEEN_OUTSIDE).commit();
    }

    public static void setActive(
            Context context,
            String mode,
            Station station
    ) {
        prefs(context).edit()
                .putString(KEY_ACTIVE_MODE, mode)
                .putString(KEY_ACTIVE_STATION, station == null ? "" : station.id)
                .putBoolean(KEY_ACTIVE_SEEN_OUTSIDE, false)
                .apply();
    }

    public static void setActiveSeenOutside(Context context, boolean seenOutside) {
        prefs(context).edit()
                .putBoolean(KEY_ACTIVE_SEEN_OUTSIDE, seenOutside)
                .apply();
    }

    public static boolean hasActiveSeenOutside(Context context) {
        return prefs(context).getBoolean(KEY_ACTIVE_SEEN_OUTSIDE, false);
    }

    public static void clearActive(Context context) {
        prefs(context).edit()
                .putString(KEY_ACTIVE_MODE, MODE_NONE)
                .remove(KEY_ACTIVE_STATION)
                .remove(KEY_ACTIVE_SEEN_OUTSIDE)
                .apply();
    }

    public static String getActiveMode(Context context) {
        return prefs(context).getString(KEY_ACTIVE_MODE, MODE_NONE);
    }

    public static Station getActiveStation(Context context) {
        return StationRepository.findById(
                context,
                prefs(context).getString(KEY_ACTIVE_STATION, null)
        );
    }

    public static void migrateForReliableArming(Context context) {
        SharedPreferences preferences = prefs(context);
        int previousSchema = preferences.getInt(KEY_STATE_SCHEMA, 0);
        if (previousSchema >= CURRENT_STATE_SCHEMA) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (Station station : StationRepository.getAll(context)) {
            if (previousSchema < 3) {
                editor.putBoolean("armed_" + station.id, false);
            }
            if (!station.enabled) {
                Station watched = new Station(station.id, station.name,
                        station.latitude, station.longitude, station.alertRadiusMeters,
                        station.rearmRadiusMeters, station.monitorRadiusMeters, true,
                        station.activationAreas);
                if (!StationRepository.upsertConfirmed(context, watched)) {
                    DetectionLogStore.append(context, "駅の監視設定を更新できませんでした");
                    return;
                }
            }
        }
        editor.remove("enabled").remove("active_test")
                .putInt(KEY_STATE_SCHEMA, CURRENT_STATE_SCHEMA).apply();
        if (previousSchema < 3) {
            DetectionLogStore.append(context, "オンにする場所へ入るまで通知はオフ");
        } else {
            DetectionLogStore.append(context, "登録した駅の自動検知を有効化");
        }
    }

    public static void removeStation(Context context, String stationId) {
        prefs(context).edit().remove("armed_" + stationId).apply();
    }
}
