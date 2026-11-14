package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class HubCatalogTest {
    private final Context c=RuntimeEnvironment.getApplication();
    @Test public void majorInterchangesAreRecognizedFromTheBundledData() throws Exception {
        assertEquals("池袋駅",HubCatalog.at(c,35.730275,139.711085,10).name);
        assertEquals("新宿駅",HubCatalog.at(c,35.689985,139.699210,10).name);
        assertEquals("北習志野駅",HubCatalog.at(c,35.7216568,140.0423544,10).name);
        assertEquals("西国分寺駅",HubCatalog.at(c,35.6999100,139.4659850,10).name);
    }
    @Test public void separateSameNamePlatformsBelongToTheSameHub() throws Exception {
        assertEquals("池袋駅",HubCatalog.at(c,35.731480,139.707720,10).name);
    }
    @Test public void adjacentDifferentNameInterchangesAreRecognized() throws Exception {
        assertEquals("朝霞台駅",HubCatalog.at(c,35.814345,139.587300,10).name);
        assertEquals("北朝霞駅",HubCatalog.at(c,35.815470,139.587230,10).name);
    }
    @Test public void ordinaryStationsDoNotShow() throws Exception {
        assertNull(HubCatalog.at(c,35.720390,139.706280,10));
        assertNull(HubCatalog.at(c,35.7266050,140.0349850,10));
        assertNull(HubCatalog.at(c,35.6721169,139.7363869,10));
    }
    @Test public void aNearbyStationMustFitTheLocationAccuracyCircle() throws Exception {
        assertNull(HubCatalog.at(c,35.733,139.711085,30));
    }
    @Test public void realRailGeometryIsNearIkebukuroAndNotAkasakaOfficeStreets() throws Exception {
        RailCatalog rail=RailCatalog.get(c);
        assertFalse(rail.near(35.720390,139.706280,70).lines.isEmpty());
        assertFalse(rail.near(35.730275,139.711085,70).lines.isEmpty());
        assertTrue(rail.near(35.6732,139.7310,70).lines.isEmpty());
    }
}
