package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import com.google.android.gms.location.Geofence;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class GeofencePlacesTest {
    @Test public void everyActivationPlaceIsRegisteredAndDeletedPlaceEventsAreIgnored() {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1",0).edit().clear().commit();
        PlaceFixtures.install(context);
        List<Geofence> fences=ReflectionHelpers.callStaticMethod(GeofenceRegistrar.class,"buildGeofences",
                ReflectionHelpers.ClassParameter.from(Context.class,context));
        assertEquals(12,fences.size());
        Set<String> ids=new HashSet<>();
        for(Geofence fence:fences) ids.add(fence.getRequestId());
        for(Station station:StationRepository.getConfiguredEnabled(context)) {
            for(ActivationArea area:station.activationAreas) {
                assertTrue(ids.contains("arm:"+station.id+":"+area.id));
                assertNotNull(GeofenceRegistrar.stationFromRequestId(context,"arm:"+station.id+":"+area.id));
            }
        }
        Station station=StationRepository.findById(context,StationRepository.ASAKADAI.id);
        String removed="arm:"+station.id+":"+station.activationAreas.get(0).id;
        StationRepository.upsert(context,new Station(station.id,station.name,station.latitude,station.longitude,
                station.alertRadiusMeters,station.rearmRadiusMeters,station.monitorRadiusMeters,
                station.enabled,java.util.Collections.singletonList(station.activationAreas.get(1))));
        assertNull(GeofenceRegistrar.stationFromRequestId(context,removed));
    }
}
