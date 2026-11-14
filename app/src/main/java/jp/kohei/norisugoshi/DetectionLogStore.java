package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DetectionLogStore {
    private static final String PREFS = "detection_logs";
    private static final String KEY_LOGS = "logs";
    private static final int MAX_ENTRIES = 200;

    private DetectionLogStore() {
    }

    public static synchronized void append(Context context, String message) {
        appendObject(context, new JSONObjectBuilder()
                .put("time", System.currentTimeMillis())
                .put("message", message)
                .build());
    }

    public static synchronized void appendLocation(
            Context context,
            Station station,
            Location location,
            double distanceMeters
    ) {
        JSONObjectBuilder builder = new JSONObjectBuilder()
                .put("time", System.currentTimeMillis())
                .put("message", String.format(
                        Locale.JAPAN,
                        "%sまで%.0fm / 精度±%.0fm",
                        station.name,
                        distanceMeters,
                        location.hasAccuracy() ? location.getAccuracy() : -1.0f
                ))
                .put("lat", location.getLatitude())
                .put("lon", location.getLongitude())
                .put("accuracy", location.hasAccuracy() ? location.getAccuracy() : -1.0f)
                .put("distance", distanceMeters);
        appendObject(context, builder.build());
    }

    private static void appendObject(Context context, JSONObject object) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        JSONArray oldArray;
        try {
            oldArray = new JSONArray(preferences.getString(KEY_LOGS, "[]"));
        } catch (JSONException exception) {
            oldArray = new JSONArray();
        }

        JSONArray newArray = new JSONArray();
        int first = Math.max(0, oldArray.length() - MAX_ENTRIES + 1);
        for (int index = first; index < oldArray.length(); index++) {
            newArray.put(oldArray.opt(index));
        }
        newArray.put(object);
        preferences.edit().putString(KEY_LOGS, newArray.toString()).apply();
    }

    public static synchronized String getRecent(Context context, int count) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
        JSONArray array;
        try {
            array = new JSONArray(preferences.getString(KEY_LOGS, "[]"));
        } catch (JSONException exception) {
            return "ログを読み込めませんでした";
        }

        if (array.length() == 0) {
            return "まだ検知ログはありません";
        }

        SimpleDateFormat formatter = new SimpleDateFormat(
                "MM-dd HH:mm:ss",
                Locale.JAPAN
        );
        StringBuilder output = new StringBuilder();
        int first = Math.max(0, array.length() - count);
        for (int index = array.length() - 1; index >= first; index--) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(formatter.format(new Date(item.optLong("time", 0L))))
                    .append("  ")
                    .append(item.optString("message", ""));
        }
        return output.toString();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LOGS)
                .apply();
    }

    private static final class JSONObjectBuilder {
        private final JSONObject object = new JSONObject();

        JSONObjectBuilder put(String key, Object value) {
            try {
                object.put(key, value);
            } catch (JSONException ignored) {
                // JSONObjectへ格納可能な値しか渡さない。
            }
            return this;
        }

        JSONObject build() {
            return object;
        }
    }
}
