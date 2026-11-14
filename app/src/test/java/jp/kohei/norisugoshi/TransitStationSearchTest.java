package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class TransitStationSearchTest {
    final double lat=35.6721169,lon=139.7363869;
    final HomeStation home=new HomeStation("朝霞台駅","東上本線",35.814345,139.5873);
    long now;List<Uri> requests;TransitLastTrainClient client;
    @Before public void reset(){now=LastTrainFixtures.night();requests=new ArrayList<>();client=new TransitLastTrainClient(this::read);}
    @After public void restoreClock(){LastTrainFixtures.reset();}
    JSONObject fixture(String name)throws IOException,JSONException{
        try(InputStream in=getClass().getResourceAsStream("/transit/akasaka/"+name)){
            if(in==null)throw new IOException("Missing fixture: "+name);
            return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    JSONObject read(Uri uri)throws IOException,JSONException{
        requests.add(uri);
        if(uri.getPath().endsWith("/feeds"))return fixture("feeds.json");
        if(uri.getPath().endsWith("/suggest"))return fixture("suggest-"+uri.getQueryParameter("q")+".json");
        assertEquals("arrival",uri.getQueryParameter("type"));assertEquals("27:59:00",uri.getQueryParameter("time"));
        String from=uri.getQueryParameter("from");
        if(from.startsWith("geo:"))return fixture("geo-arrival.json");
        assertEquals("tokyo-tobu-rail-part-02:Tobu.Tojo.Asakadai",uri.getQueryParameter("to"));
        return fixture("station-arrival-"+from.substring(from.lastIndexOf(':')+1)+".json");
    }
    @Test public void akasakaUsesNearbyMetroInsteadOfWalkingToIkebukuro()throws Exception{
        LastTrainRoute route=client.search(RuntimeEnvironment.getApplication(),lat,lon,home,now);
        assertNotEquals("池袋",route.station);assertTrue(route.path.contains("池袋"));assertTrue(route.path.endsWith("朝霞台"));
        assertTrue(route.leaveAt>=OffsetDateTime.parse("2026-10-13T23:30:00+09:00").toInstant().toEpochMilli());
        assertTrue(route.leaveAt<OffsetDateTime.parse("2026-10-14T00:00:00+09:00").toInstant().toEpochMilli());
        assertTrue(requests.stream().anyMatch(u->u.getQueryParameter("from")!=null&&u.getQueryParameter("from").contains("AkasakaMitsuke")));
        assertFalse(requests.stream().anyMatch(u->u.getQueryParameter("from")!=null&&u.getQueryParameter("from").contains("福岡")));
    }
    @Test public void longAccessAndLongEgressInRealGeoResponseCannotCreateAlarm()throws Exception{
        try{TransitLastTrainClient.parse(fixture("geo-arrival.json"),fixture("feeds.json"),"2026-10-13",lat,lon,now);fail();}
        catch(TransitLastTrainClient.DataUnavailable expected){}
    }
    @Test public void morningClientEntryDoesNotFetchEvenMetadata()throws Exception{
        long morning=OffsetDateTime.parse("2026-10-13T09:54:00+09:00").toInstant().toEpochMilli();
        try{client.search(RuntimeEnvironment.getApplication(),lat,lon,home,morning);fail();}
        catch(TransitLastTrainClient.DataUnavailable expected){}
        assertTrue(requests.isEmpty());
    }
    @Test public void stationAndFeedMetadataAreReusedAtSameLocation()throws Exception{
        client.search(RuntimeEnvironment.getApplication(),lat,lon,home,now);int first=requests.size();
        client.search(RuntimeEnvironment.getApplication(),lat,lon,home,now+60_000);
        assertFalse(requests.subList(first,requests.size()).stream().anyMatch(u->u.getPath().endsWith("/suggest")||u.getPath().endsWith("/feeds")));
    }
    @Test public void olderUnrestrictedRouteCacheIsRejected()throws Exception{
        LastTrainRoute old=new LastTrainRoute("2026-10-13","池袋","池袋 → 朝霞台",now+60_000,now+600_000,now,lat,lon,false);
        JSONObject json=old.json();json.remove("policy");
        try{LastTrainRoute.from(json);fail();}catch(IllegalArgumentException expected){}
        assertEquals(old.leaveAt,LastTrainRoute.from(old.json()).leaveAt);
    }
}
