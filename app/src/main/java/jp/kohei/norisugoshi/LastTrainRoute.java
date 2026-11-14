package jp.kohei.norisugoshi;

import org.json.JSONException;
import org.json.JSONObject;

final class LastTrainRoute {
    final String day, station, path;
    final long leaveAt, arriveAt, fetchedAt;
    final double originLat, originLon;
    final boolean walkOnly;
    String credits = "";
    LastTrainRoute(String day, String station, String path, long leaveAt, long arriveAt,
                   long fetchedAt, double originLat, double originLon, boolean walkOnly) {
        if (day == null || !day.matches("\\d{4}-\\d{2}-\\d{2}") || leaveAt <= 0 || arriveAt < leaveAt
                || !Double.isFinite(originLat) || !Double.isFinite(originLon)) throw new IllegalArgumentException("Invalid route");
        this.day=day; this.station=station; this.path=path; this.leaveAt=leaveAt;
        this.arriveAt=arriveAt; this.fetchedAt=fetchedAt; this.originLat=originLat; this.originLon=originLon; this.walkOnly=walkOnly;
    }
    long alarmAt(int minutes) { return leaveAt - minutes * 60_000L; }
    JSONObject json() throws JSONException {
        return new JSONObject().put("policy",1).put("day",day).put("station",station).put("path",path)
                .put("leave",leaveAt).put("arrive",arriveAt).put("fetched",fetchedAt)
                .put("lat",originLat).put("lon",originLon).put("walk",walkOnly).put("credits",credits);
    }
    static LastTrainRoute from(JSONObject o) throws JSONException {
        if(o.optInt("policy",0)!=1)throw new IllegalArgumentException("Old route policy");
        LastTrainRoute r=new LastTrainRoute(o.getString("day"),o.getString("station"),o.getString("path"),
                o.getLong("leave"),o.getLong("arrive"),o.getLong("fetched"),o.getDouble("lat"),o.getDouble("lon"),o.optBoolean("walk"));
        r.credits=o.optString("credits","");return r;
    }
}
