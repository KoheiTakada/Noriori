package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class LastTrainStore {
    static final String QUIET_AREA_ID = "home-quiet-area";
    static final double DEFAULT_QUIET_RADIUS = 500;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("last_train_v1",0); }
    static synchronized HomeStation home(Context c) {
        String text = prefs(c).getString("homeStation", "");
        if (!text.isEmpty()) {
            try { HomeStation station = HomeStation.from(new JSONObject(text)); ensureAreas(c, station); return station; }
            catch (JSONException | IllegalArgumentException e) { return null; }
        }
        ReturnArea legacy = area(prefs(c).getString("home", ""));
        if (legacy == null) return null;
        try {
            List<StationCatalog.Entry> nearest = StationCatalog.suggest(c, "", legacy.latitude, legacy.longitude, 1);
            if (nearest.isEmpty()) return null;
            HomeStation station = new HomeStation(nearest.get(0));
            ReturnArea quiet = new ReturnArea(QUIET_AREA_ID, "通知しないエリア", legacy.latitude, legacy.longitude, legacy.radius);
            return saveHome(c, station, quiet) ? station : null;
        } catch (java.io.IOException e) {
            DetectionLogStore.append(c, "家の最寄り駅の移行待ち"); return null;
        }
    }
    static ReturnArea area(String text) {
        try { return ReturnArea.from(new JSONObject(text)); } catch(JSONException | IllegalArgumentException e) { return null; }
    }
    static ReturnArea notificationArea(Context c) { return area(prefs(c).getString("notificationArea", "")); }
    static ReturnArea quietArea(Context c) { return area(prefs(c).getString("quietArea", "")); }
    static ReturnArea defaultQuietArea(HomeStation home) {
        return new ReturnArea(QUIET_AREA_ID, "通知しないエリア", home.latitude, home.longitude, DEFAULT_QUIET_RADIUS);
    }
    static ReturnArea defaultArea(HomeStation home) {
        return new ReturnArea("notification-area", "通知するエリア", home == null ? 35.8 : home.latitude,
                home == null ? 139.7 : home.longitude, ReturnArea.DEFAULT_RADIUS);
    }
    private static void ensureAreas(Context c, HomeStation station) throws JSONException {
        if (notificationArea(c) == null) saveArea(c, defaultArea(station));
        // Adding the missing circle on upgrade must keep the current route and reservations.
        if (quietArea(c) == null) prefs(c).edit().putString("quietArea", defaultQuietArea(station).json().toString()).commit();
    }
    static synchronized boolean saveHome(Context c, HomeStation station) {
        return saveHome(c, station, null);
    }
    private static boolean saveHome(Context c, HomeStation station, ReturnArea initialQuiet) {
        try {
            SharedPreferences.Editor edit=invalidated(c).putString("homeStation", station.json().toString()).remove("home");
            if (notificationArea(c) == null) edit.putString("notificationArea", defaultArea(station).json().toString());
            HomeStation previous = null;
            try { previous = HomeStation.from(new JSONObject(prefs(c).getString("homeStation", ""))); }
            catch (JSONException | IllegalArgumentException ignored) { }
            ReturnArea quiet = initialQuiet == null ? quietArea(c) : initialQuiet;
            if (quiet == null) quiet = defaultQuietArea(station);
            else if (initialQuiet == null && (previous == null || previous.latitude != station.latitude || previous.longitude != station.longitude))
                quiet = new ReturnArea(QUIET_AREA_ID, "通知しないエリア", station.latitude, station.longitude, quiet.radius);
            edit.putString("quietArea", quiet.json().toString());
            return edit.commit();
        } catch (JSONException e) { return false; }
    }
    private static SharedPreferences.Editor invalidated(Context c) {
        return prefs(c).edit().putLong("generation", generation(c)+1).remove("route").remove("problem")
                .remove("zone").remove("retryAt").remove("errorNotified").putBoolean("alerting",false);
    }
    static synchronized boolean saveArea(Context c, ReturnArea area) {
        try {
            return invalidated(c).putString("notificationArea",area.json().toString()).commit();
        } catch(JSONException e){return false;}
    }
    static synchronized boolean saveQuietArea(Context c, ReturnArea area) {
        if (home(c) == null) return false;
        try {
            ReturnArea quiet = new ReturnArea(QUIET_AREA_ID, "通知しないエリア", area.latitude, area.longitude, area.radius);
            return invalidated(c).putString("quietArea", quiet.json().toString()).commit();
        } catch (JSONException | IllegalArgumentException e) { return false; }
    }
    static synchronized boolean deleteHome(Context c) { return invalidated(c).remove("home").remove("homeStation").remove("quietArea").commit(); }
    static long generation(Context c){return prefs(c).getLong("generation",0);}
    static LastTrainRoute route(Context c) {
        try{return LastTrainRoute.from(new JSONObject(prefs(c).getString("route","")));}
        catch(JSONException | IllegalArgumentException e){return null;}
    }
    static void route(Context c,LastTrainRoute r){
        try{prefs(c).edit().putString("route",r.json().toString()).remove("problem").commit();}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    static int minutes(Context c){return prefs(c).getInt("minutes",15);}
    static void minutes(Context c,int minutes){
        if(minutes<5||minutes>60)throw new IllegalArgumentException("Invalid reminder");
        prefs(c).edit().putInt("minutes",minutes).commit();
    }
    static String problem(Context c){return prefs(c).getString("problem","");}
    static void problem(Context c,String p){prefs(c).edit().putString("problem",p).apply();}
    static boolean muted(Context c,long now){return LastTrainEngine.day(now).equals(prefs(c).getString("mutedDay",""));}
    static void mute(Context c,long now){prefs(c).edit().putString("mutedDay",LastTrainEngine.day(now)).putBoolean("alerting",false).commit();}
    static boolean alerting(Context c){return prefs(c).getBoolean("alerting",false);}
    static void alerting(Context c,boolean active){prefs(c).edit().putBoolean("alerting",active).commit();}
    static boolean announced(Context c,LastTrainRoute r){return r.day.equals(prefs(c).getString("announcedDay",""));}
    static void markAnnounced(Context c,LastTrainRoute r){prefs(c).edit().putString("announcedDay",r.day).commit();}
}
