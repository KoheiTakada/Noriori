package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class StationRepository {
    public static final Station ASAKADAI = new Station(
            "asakadai",
            "朝霞台駅",
            35.8146719,
            139.5867757
    );

    public static final Station IKEBUKURO = new Station(
            "ikebukuro_tobu",
            "池袋駅",
            35.7311500,
            139.7111325,
            125, 1_125, 5_000, true,
            Arrays.asList(
                    new ActivationArea("目白駅", 35.7203900, 139.7062800, 500),
                    new ActivationArea("大塚駅", 35.7317850, 139.7279400, 500),
                    new ActivationArea("十条駅", 35.7603900, 139.7222500, 500),
                    new ActivationArea("大山駅", 35.7483744, 139.7026412, 500),
                    new ActivationArea("椎名町駅", 35.7264950, 139.6943150, 500)
            )
    );

    public static final List<Station> DEFAULTS = Collections.unmodifiableList(
            Collections.singletonList(IKEBUKURO)
    );

    private static final String PREFS = "station_rules_v1";
    private static final String KEY_STATIONS = "stations_json";
    private static final String KEY_DEFAULTS_REVISION = "defaults_revision";

    private StationRepository() {
    }

    public static List<Station> getAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_STATIONS, null);
        if (stored == null) {
            return new ArrayList<>(DEFAULTS);
        }

        try {
            JSONArray array = new JSONArray(stored);
            List<Station> stations = new ArrayList<>();
            boolean updateDefaults = prefs.getInt(KEY_DEFAULTS_REVISION, 0) < 1;
            for (int i = 0; i < array.length(); i++) {
                Station station = fromJson(array.getJSONObject(i));
                if (station != null) {
                    // Only untouched legacy presets change. User-created circles stay as saved.
                    if (updateDefaults && isUntouchedPreset(station, ASAKADAI)) {
                        continue;
                    }
                    if (updateDefaults && isUntouchedPreset(station, IKEBUKURO)) {
                        station = IKEBUKURO;
                    }
                    stations.add(station);
                }
            }
            if (updateDefaults) save(context, stations);
            return stations;
        } catch (JSONException exception) {
            DetectionLogStore.append(context, "駅設定を読み込めないため初期値を使用");
            return new ArrayList<>(DEFAULTS);
        }
    }

    private static boolean isUntouchedPreset(Station station, Station preset) {
        return station.id.equals(preset.id) && station.name.equals(preset.name)
                && station.latitude == preset.latitude && station.longitude == preset.longitude
                && station.alertRadiusMeters == 500 && station.activationAreas.isEmpty();
    }

    public static List<Station> getEnabled(Context context) {
        List<Station> enabled = new ArrayList<>();
        for (Station station : getAll(context)) {
            if (station.enabled) {
                enabled.add(station);
            }
        }
        return enabled;
    }

    public static Station findById(Context context, String id) {
        if (id == null) {
            return null;
        }
        for (Station station : getAll(context)) {
            if (station.id.equals(id)) {
                return station;
            }
        }
        return null;
    }

    public static List<Station> getConfiguredEnabled(Context context) {
        List<Station> result = new ArrayList<>();
        for (Station station : getEnabled(context)) {
            if (station.isConfigured()) result.add(station);
        }
        return result;
    }

    public static Station firstEnabled(Context context) {
        List<Station> stations = getEnabled(context);
        return stations.isEmpty() ? null : stations.get(0);
    }

    public static String newId() {
        return "station_" + UUID.randomUUID();
    }

    public static void upsert(Context context, Station station) {
        upsertInternal(context, station, false);
    }

    public static synchronized boolean upsertConfirmed(Context context, Station station) {
        return upsertInternal(context, station, true);
    }

    private static boolean upsertInternal(Context context, Station station, boolean confirmed) {
        List<Station> stations = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).id.equals(station.id)) {
                stations.set(i, station);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            stations.add(station);
        }
        return save(context, stations, confirmed);
    }

    public static void delete(Context context, String stationId) {
        List<Station> stations = getAll(context);
        stations.removeIf(station -> station.id.equals(stationId));
        save(context, stations);
        MonitorStateStore.removeStation(context, stationId);
    }

    private static void save(Context context, List<Station> stations) {
        save(context, stations, false);
    }

    private static boolean save(Context context, List<Station> stations, boolean confirmed) {
        JSONArray array = new JSONArray();
        for (Station station : stations) {
            array.put(toJson(station));
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DEFAULTS_REVISION, 1)
                .putString(KEY_STATIONS, array.toString());
        if (confirmed) {
            return editor.commit();
        }
        editor.apply();
        return true;
    }

    private static JSONObject toJson(Station station) {
        JSONObject json = new JSONObject();
        RadiusSettings.Values radii = RadiusSettings.automaticForAlertRadius(station.alertRadiusMeters);
        try {
            json.put("id", station.id);
            json.put("name", station.name);
            json.put("latitude", station.latitude);
            json.put("longitude", station.longitude);
            json.put("alertRadiusMeters", radii.alertMeters);
            json.put("rearmRadiusMeters", radii.rearmMeters);
            json.put("monitorRadiusMeters", radii.monitorMeters);
            json.put("enabled", station.enabled);
            JSONArray areas = new JSONArray();
            for (ActivationArea area : station.activationAreas) {
                areas.put(new JSONObject().put("name", area.name)
                        .put("latitude", area.latitude).put("longitude", area.longitude)
                        .put("radiusMeters", area.radiusMeters));
            }
            json.put("activations", areas);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return json;
    }

    private static Station fromJson(JSONObject json) {
        try {
            RadiusSettings.Values radii = RadiusSettings.automaticForAlertRadius(
                    json.optDouble("alertRadiusMeters", 500.0));
            List<ActivationArea> activations = new ArrayList<>();
            JSONArray areas = json.optJSONArray("activations");
            if (areas == null) {
                areas = new JSONArray();
                JSONObject oldArea = json.optJSONObject("activation");
                if (oldArea != null) areas.put(oldArea);
            }
            for (int i = 0; i < areas.length(); i++) {
                JSONObject area = areas.getJSONObject(i);
                activations.add(new ActivationArea(area.getString("name"), area.getDouble("latitude"),
                        area.getDouble("longitude"), area.optDouble("radiusMeters", 500.0)));
            }
            return new Station(
                    json.getString("id"),
                    json.getString("name"),
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                    radii.alertMeters,
                    radii.rearmMeters,
                    radii.monitorMeters,
                    json.optBoolean("enabled", true), activations
            );
        } catch (JSONException | IllegalArgumentException exception) {
            return null;
        }
    }
}
