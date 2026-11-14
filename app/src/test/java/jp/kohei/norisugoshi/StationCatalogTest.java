package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class StationCatalogTest {
    @Test public void bundledStationsCoverJapanAndHaveValidCoordinates() throws Exception {
        List<StationCatalog.Entry> stations = StationCatalog.all(RuntimeEnvironment.getApplication());
        assertTrue(stations.size() > 9_000);
        for (StationCatalog.Entry entry : stations) {
            assertTrue(Double.isFinite(entry.latitude));
            assertTrue(Double.isFinite(entry.longitude));
            assertTrue(entry.latitude >= 20 && entry.latitude <= 46);
            assertTrue(entry.longitude >= 122 && entry.longitude <= 146);
        }
    }
    @Test public void emptyInputOffersTheClosestStations() throws Exception {
        List<StationCatalog.Entry> stations = StationCatalog.suggest(RuntimeEnvironment.getApplication(),
                "", 35.7211, 140.0411, 8);
        assertEquals(8, stations.size());
        assertEquals("北習志野駅", stations.get(0).name);
        double previous = -1;
        for (StationCatalog.Entry entry : stations) {
            double distance = Geo.distanceMeters(35.7211, 140.0411, entry.latitude, entry.longitude);
            assertTrue(distance >= previous); previous = distance;
        }
    }
    @Test public void partialInputAndStationSuffixFindTheSameStationOffline() throws Exception {
        for (String query : new String[]{"北習", " 北習志野駅 "}) {
            List<StationCatalog.Entry> stations = StationCatalog.suggest(RuntimeEnvironment.getApplication(),
                    query, StationRepository.ASAKADAI.latitude, StationRepository.ASAKADAI.longitude, 8);
            assertFalse(stations.isEmpty());
            assertEquals("北習志野駅", stations.get(0).name);
        }
    }
}
