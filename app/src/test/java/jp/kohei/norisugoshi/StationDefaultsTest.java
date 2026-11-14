package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class StationDefaultsTest {
    private Context context;
    @Before public void clear() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
    }
    @Test public void firstInstallHasOnlyIkebukuroAndFiveUsableOnAreas() {
        List<Station> stations = StationRepository.getAll(context);
        assertEquals(1, stations.size());
        Station station = stations.get(0);
        assertEquals("池袋駅", station.name);
        assertEquals(125, station.alertRadiusMeters, 0);
        for (ActivationArea area : station.activationAreas) assertEquals(500, area.radiusMeters, 0);
        assertEquals(Arrays.asList("目白駅", "大塚駅", "十条駅", "大山駅", "椎名町駅"),
                station.activationAreas.stream().map(a -> a.name).collect(Collectors.toList()));
        assertTrue(station.isConfigured());
        for (ActivationArea area : station.activationAreas) assertTrue(area.isSeparateFrom(station));
        assertFalse(MonitorStateStore.isArmed(context, station));
    }
    @Test public void untouchedOldPresetsUseTheNewDefaultsOnUpdate() throws Exception {
        JSONArray legacy = new JSONArray().put(oldPreset(StationRepository.ASAKADAI))
                .put(oldPreset(StationRepository.IKEBUKURO));
        context.getSharedPreferences("station_rules_v1", 0).edit()
                .putString("stations_json", legacy.toString()).commit();
        assertEquals(1, StationRepository.getAll(context).size());
        assertNull(StationRepository.findById(context, StationRepository.ASAKADAI.id));
        assertEquals(5, StationRepository.findById(context, StationRepository.IKEBUKURO.id).activationAreas.size());
    }
    @Test public void savedCustomStationsAndCirclesStayUnchanged() throws Exception {
        JSONObject asakadai = oldPreset(StationRepository.ASAKADAI).put("alertRadiusMeters", 350);
        JSONObject ikebukuro = oldPreset(StationRepository.IKEBUKURO).put("activations",
                new JSONArray().put(new JSONObject().put("name", "自分の場所")
                        .put("latitude", 35.76039).put("longitude", 139.72225).put("radiusMeters", 280)));
        context.getSharedPreferences("station_rules_v1", 0).edit()
                .putString("stations_json", new JSONArray().put(asakadai).put(ikebukuro).toString()).commit();
        assertEquals(2, StationRepository.getAll(context).size());
        assertEquals(350, StationRepository.findById(context, StationRepository.ASAKADAI.id).alertRadiusMeters, 0);
        ActivationArea area = StationRepository.findById(context, StationRepository.IKEBUKURO.id).activationAreas.get(0);
        assertEquals("自分の場所", area.name);
        assertEquals(280, area.radiusMeters, 0);
    }
    @Test public void deletingTheDefaultStationDoesNotRecreateIt() {
        StationRepository.delete(context, StationRepository.IKEBUKURO.id);
        assertTrue(StationRepository.getAll(context).isEmpty());
        assertTrue(StationRepository.getAll(context).isEmpty());
    }
    private JSONObject oldPreset(Station station) throws Exception {
        return new JSONObject().put("id", station.id).put("name", station.name)
                .put("latitude", station.latitude).put("longitude", station.longitude)
                .put("alertRadiusMeters", 500).put("enabled", true).put("activations", new JSONArray());
    }
}
