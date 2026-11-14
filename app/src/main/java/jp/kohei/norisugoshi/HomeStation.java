package jp.kohei.norisugoshi;

import org.json.JSONException;
import org.json.JSONObject;

/** The last train destination is a station, not a user-defined area. */
final class HomeStation {
    final String name, lines;
    final double latitude, longitude;
    HomeStation(String name, String lines, double latitude, double longitude) {
        if (name == null || name.trim().isEmpty() || !Double.isFinite(latitude)
                || Math.abs(latitude) > 85 || !Double.isFinite(longitude) || Math.abs(longitude) > 180)
            throw new IllegalArgumentException("Invalid home station");
        this.name = name.trim(); this.lines = lines == null ? "" : lines;
        this.latitude = latitude; this.longitude = longitude;
    }
    HomeStation(StationCatalog.Entry station) {
        this(station.name, station.lines, station.latitude, station.longitude);
    }
    JSONObject json() throws JSONException {
        return new JSONObject().put("name", name).put("lines", lines).put("lat", latitude).put("lon", longitude);
    }
    static HomeStation from(JSONObject o) throws JSONException {
        return new HomeStation(o.getString("name"), o.optString("lines"), o.getDouble("lat"), o.getDouble("lon"));
    }
}
