package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoTest {
    @Test
    public void samePointIsZero() {
        assertEquals(0.0, Geo.distanceMeters(35.0, 139.0, 35.0, 139.0), 0.001);
    }

    @Test
    public void fixedStationsArePlausiblySeparated() {
        double distance = Geo.distanceMeters(
                StationRepository.ASAKADAI.latitude,
                StationRepository.ASAKADAI.longitude,
                StationRepository.IKEBUKURO.latitude,
                StationRepository.IKEBUKURO.longitude
        );
        assertTrue(distance > 10_000.0);
        assertTrue(distance < 20_000.0);
    }
}
