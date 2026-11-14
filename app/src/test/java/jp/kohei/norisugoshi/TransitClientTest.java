package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class TransitClientTest {
    private final String night="2026-10-12";
    private long time(String value){return OffsetDateTime.parse(value).toInstant().toEpochMilli();}
    private JSONObject fixture(String name)throws Exception{
        try(InputStream in=getClass().getResourceAsStream("/transit/"+name)){
            assertNotNull(name,in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private JSONObject plan()throws Exception{return fixture("transit-arrival-20261012-2759.json");}
    private JSONObject one()throws Exception{
        JSONObject d=plan();d.put("journeys",new JSONArray().put(d.getJSONArray("journeys").getJSONObject(1)));return d;
    }
    private JSONObject journey(JSONObject d)throws Exception{return d.getJSONArray("journeys").getJSONObject(0);}
    private JSONObject leg(JSONObject d)throws Exception{return journey(d).getJSONArray("legs").getJSONObject(0);}
    private LastTrainRoute parse(JSONObject d)throws Exception{
        return TransitLastTrainClient.parse(d,fixture("feeds.json"),night,35.73115,139.71113,time("2026-10-12T22:00:00+09:00"));
    }
    private void reject(JSONObject d)throws Exception{
        try{parse(d);fail("Invalid data must not create an alarm");}catch(TransitLastTrainClient.DataUnavailable expected){}
    }
    @Test public void liveBoundedResponseSubtractsAccessWalkAndPlatformMarginExactlyOnce()throws Exception{
        LastTrainRoute r=parse(plan());
        assertEquals(time("2026-10-13T00:23:51+09:00"),r.leaveAt);
        assertEquals(time("2026-10-13T00:08:51+09:00"),r.alarmAt(15));
        assertEquals(time("2026-10-13T00:50:02+09:00"),r.arriveAt);
        assertEquals("池袋",r.station);assertEquals("池袋 → 朝霞台",r.path);assertFalse(r.walkOnly);
        assertTrue(r.credits.contains("Akihiko Kusanagi"));
    }
    @Test public void liveLastModeFollowingMorningConnectionIsRejected()throws Exception{reject(fixture("transit-plan.json"));}
    @Test public void nextCalendarDateAndNegativeServiceSecondsHaveSameAbsoluteDeadline()throws Exception{
        assertEquals(parse(plan()).leaveAt,parse(fixture("transit-arrival-20261013-0359.json")).leaveAt);
        JSONObject d=fixture("transit-arrival-20261013-0359.json");
        d.put("journeys",new JSONArray().put(d.getJSONArray("journeys").getJSONObject(6)));
        assertEquals(time("2026-10-12T23:43:51+09:00"),parse(d).leaveAt);
    }
    @Test public void lastTrainBefore23StillProducesAnAlarm()throws Exception{
        JSONObject d=one(),j=journey(d),l=leg(d);j.put("departureSecs",22*3600+40*60).put("arrivalSecs",23*3600+10*60);
        l.put("departureSecs",22*3600+40*60).put("arrivalSecs",23*3600+9*60);
        assertEquals(time("2026-10-12T22:18:51+09:00"),parse(d).alarmAt(15));
    }
    @Test public void longWalkFallbackDoesNotSilenceRailReminder()throws Exception{
        JSONObject d=plan();d.put("journeys",new JSONArray().put(d.getJSONArray("journeys").getJSONObject(0)));reject(d);
    }
    @Test public void ordinaryShortWalkCanSuppressReminder()throws Exception{
        JSONObject d=one(),j=journey(d),l=leg(d);j.put("durationSecs",1200).put("departureSecs",79000).put("arrivalSecs",80200);
        l.put("kind","walk").put("departureSecs",79000).put("arrivalSecs",80200);
        assertTrue(parse(d).walkOnly);
    }
    @Test public void averageFrequencyTimesDoNotCreateReservation()throws Exception{
        JSONObject d=one();leg(d).put("headwayBased",true);reject(d);
    }
    @Test public void busAndExtraFareTrainDoNotPassPolicy()throws Exception{
        JSONObject d=one();leg(d).put("mode","bus");reject(d);
        d=one();leg(d).put("routeName","TJライナー");reject(d);
        d=one();leg(d).put("trainType","odpt.TrainType:JR-East.LimitedExpress");reject(d);
    }
    @Test public void staleOrExpiredFeedCannotCreateReservation()throws Exception{
        JSONObject f=fixture("feeds.json");f.getJSONArray("feeds").getJSONObject(0).put("stale",true);
        try{TransitLastTrainClient.parse(one(),f,night,35.7,139.7,1);fail();}
        catch(TransitLastTrainClient.DataUnavailable e){assertEquals("時刻表が古い",e.getMessage());}
        f=fixture("feeds.json");f.getJSONArray("feeds").getJSONObject(0).put("serviceEnd","20261011");
        try{TransitLastTrainClient.parse(one(),f,night,35.7,139.7,1);fail();}catch(TransitLastTrainClient.DataUnavailable expected){}
        f=fixture("feeds.json");f.getJSONArray("feeds").getJSONObject(0).put("serviceStart","20261013");
        try{TransitLastTrainClient.parse(one(),f,night,35.7,139.7,1);fail();}catch(TransitLastTrainClient.DataUnavailable expected){}
    }
    @Test public void impossibleTransferIsRejected()throws Exception{
        JSONObject d=one(),l=leg(d);JSONObject second=new JSONObject(l.toString());
        second.put("departureSecs",l.getLong("arrivalSecs")-1);journey(d).getJSONArray("legs").put(second);reject(d);
    }
    @Test public void stationWithoutKnownSourceIsRejected()throws Exception{
        JSONObject d=one();leg(d).getJSONObject("from").put("id","unknown:station");reject(d);
    }
    @Test public void responseWithoutJourneysIsUnconfirmedRatherThanMissed()throws Exception{
        JSONObject d=one();d.put("journeys",new JSONArray());reject(d);
    }
    @Test public void attributionsSurviveAppRestart()throws Exception{
        LastTrainRoute r=parse(plan());LastTrainRoute saved=LastTrainRoute.from(r.json());
        assertEquals(r.credits,saved.credits);assertEquals(r.leaveAt,saved.leaveAt);
    }
}
