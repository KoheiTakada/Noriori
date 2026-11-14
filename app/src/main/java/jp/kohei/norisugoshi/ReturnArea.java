package jp.kohei.norisugoshi;

import org.json.JSONException;
import org.json.JSONObject;

final class ReturnArea {
    static final double MIN_RADIUS = 100, DEFAULT_RADIUS = 150_000, MAX_RADIUS = 1_000_000;
    final String id, name;
    final double latitude, longitude, radius;
    ReturnArea(String id, String name, double latitude, double longitude, double radius) {
        if (id == null || id.isEmpty() || name == null || name.trim().isEmpty()
                || !Double.isFinite(latitude) || Math.abs(latitude) > 85
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                || !Double.isFinite(radius) || radius < MIN_RADIUS || radius > MAX_RADIUS)
            throw new IllegalArgumentException("Invalid area");
        this.id=id; this.name=name.trim(); this.latitude=latitude; this.longitude=longitude; this.radius=radius;
    }
    JSONObject json() throws JSONException {
        return new JSONObject().put("id",id).put("name",name).put("lat",latitude).put("lon",longitude).put("radius",radius);
    }
    static ReturnArea from(JSONObject o) throws JSONException {
        return new ReturnArea(o.getString("id"),o.getString("name"),o.getDouble("lat"),o.getDouble("lon"),o.getDouble("radius"));
    }
    static double radiusAt(int progress) {
        return MIN_RADIUS * Math.pow(MAX_RADIUS / MIN_RADIUS, Math.max(0, Math.min(1000, progress)) / 1000.0);
    }
    static int progressAt(double radius) {
        return (int) Math.round(1000 * Math.log(Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius)) / MIN_RADIUS)
                / Math.log(MAX_RADIUS / MIN_RADIUS));
    }
}
