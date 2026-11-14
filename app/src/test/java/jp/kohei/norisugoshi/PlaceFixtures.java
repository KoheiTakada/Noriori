package jp.kohei.norisugoshi;

import android.content.Context;
import java.util.Arrays;

final class PlaceFixtures {
    static Station configured(Station station) {
        double offset = Math.toDegrees(3_000.0 / 6_371_000.0);
        return new Station(station.id, station.name, station.latitude, station.longitude,
                station.alertRadiusMeters, station.rearmRadiusMeters, station.monitorRadiusMeters,
                station.enabled, Arrays.asList(
                        new ActivationArea("北側の駅", station.latitude + offset, station.longitude, 500),
                        new ActivationArea("南側の駅", station.latitude - offset, station.longitude, 500)));
    }
    static void install(Context context) {
        context.getSharedPreferences("station_rules_v1", 0).edit()
                .putString("stations_json", "[]").commit();
        for (Station station : Arrays.asList(StationRepository.ASAKADAI, StationRepository.IKEBUKURO)) {
            StationRepository.upsert(context, configured(station));
        }
    }
}
