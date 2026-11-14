package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.*;
import android.location.Location;
import android.os.Bundle;
import android.view.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class LastTrainTest {
    private Context context;
    private long now;
    @After public void restoreClock(){LastTrainFixtures.reset();}
    @Before public void setup(){LastTrainFixtures.night();
        context=RuntimeEnvironment.getApplication();
        LastTrainStore.prefs(context).edit().clear().commit();
        now=LastTrainEngine.now();
    }
    private long time(String s){return OffsetDateTime.parse(s).toInstant().toEpochMilli();}
    private HomeStation home(){return new HomeStation("朝霞台駅","東武東上線",35.8,139.5);}
    private LastTrainRoute route(long leave){return new LastTrainRoute(LastTrainEngine.day(now),"池袋","東武東上線",leave,leave+60*60_000L,now,35.73,139.71,false);}
    @Test public void midnightDoesNotChangeNightOrMute(){
        long before=time("2026-10-12T23:59:00+09:00"),after=time("2026-10-13T00:30:00+09:00");
        assertEquals(LastTrainEngine.day(before),LastTrainEngine.day(after));
        LastTrainStore.mute(context,before);assertTrue(LastTrainStore.muted(context,after));
        assertFalse(LastTrainStore.muted(context,time("2026-10-13T12:00:00+09:00")));
    }
    @Test public void overdueReminderRingsOnlyWhileStillPossible(){
        LastTrainRoute r=route(now+5*60_000L);
        assertEquals(LastTrainEngine.Decision.ALERT,LastTrainEngine.decide(r,15,now,false,false,"notify"));
        assertEquals(LastTrainEngine.Decision.MISSED,LastTrainEngine.decide(r,15,r.leaveAt+1,false,false,"notify"));
    }
    @Test public void stopAndReopenDoesNotRingAgain(){
        LastTrainRoute r=route(now+5*60_000L);LastTrainStore.route(context,r);LastTrainStore.mute(context,now);
        LastTrainCoordinator.restore(context);assertFalse(LastTrainStore.alerting(context));
        assertEquals(LastTrainEngine.Decision.NONE,LastTrainEngine.decide(LastTrainStore.route(context),15,now,LastTrainStore.muted(context,now),false,"notify"));
    }
    @Test public void cachedRouteSchedulesWithoutNewGpsAndCancelRemovesBothAlarms(){
        LastTrainStore.saveHome(context,home());
        LastTrainRoute r=route(now+60*60_000L);LastTrainStore.route(context,r);
        LastTrainStore.prefs(context).edit().putString("zone","notify").commit();
        LastTrainCoordinator.restore(context);
        org.robolectric.shadows.ShadowAlarmManager alarms=Shadows.shadowOf(context.getSystemService(android.app.AlarmManager.class));
        assertEquals(2,alarms.getScheduledAlarms().size());
        assertEquals(r.alarmAt(15)-5*60_000L,alarms.getNextScheduledAlarm().triggerAtTime);
        LastTrainAlarms.cancel(context);assertTrue(alarms.getScheduledAlarms().isEmpty());
        assertEquals(r.leaveAt,LastTrainStore.route(context).leaveAt);
    }
    @Test public void appearingInsideAreaWithoutPriorGpsIsSuppressed(){
        assertEquals("home",LastTrainEngine.zone(35.8,139.5,10,LastTrainStore.defaultQuietArea(home()),LastTrainStore.defaultArea(home()),"unknown"));
        assertEquals("travel",LastTrainEngine.zone(43,141,10,LastTrainStore.defaultQuietArea(home()),LastTrainStore.defaultArea(home()),"unknown"));
        assertEquals("notify",LastTrainEngine.zone(35.73,139.71,10,LastTrainStore.defaultQuietArea(home()),LastTrainStore.defaultArea(home()),"unknown"));
    }
    @Test public void InaccurateFixCannotCancelProtection(){
        assertEquals("notify",LastTrainEngine.zone(35.8,139.5,1000,LastTrainStore.defaultQuietArea(home()),LastTrainStore.defaultArea(home()),"notify"));
        assertEquals("notify",LastTrainEngine.zone(36,139.7,10,LastTrainStore.defaultQuietArea(home()),LastTrainStore.defaultArea(home()),"unknown"));
    }
    @Test public void arrivalHomeClearsNightMuteForNextOuting(){
        LastTrainStore.saveHome(context,home());LastTrainStore.mute(context,now);
        LastTrainStore.prefs(context).edit().putString("zone","notify").commit();
        Location fix=new Location("test");fix.setLatitude(home().latitude);fix.setLongitude(home().longitude);fix.setAccuracy(10);fix.setTime(now);
        LastTrainCoordinator.observe(context,fix);assertFalse(LastTrainStore.muted(context,now));
        assertEquals("home",LastTrainStore.prefs(context).getString("zone",""));
    }
    @Test public void regionReplacementKeepsHomeStationAndReminder() {
        LastTrainStore.saveHome(context,home());LastTrainStore.minutes(context,30);
        LastTrainStore.saveArea(context,new ReturnArea("notification-area","通知するエリア",43,141,300_000));
        assertEquals("朝霞台駅",LastTrainStore.home(context).name);
        assertEquals(300_000,LastTrainStore.notificationArea(context).radius,0);
        assertEquals(30,LastTrainStore.minutes(context));
    }
    @Test public void regionSavesTheMapPreviewWithoutAnyStationPicker() {
        try(ActivityController<ReturnAreaEditorActivity> a=Robolectric.buildActivity(ReturnAreaEditorActivity.class).setup()){
            android.widget.Button save=ReflectionHelpers.getField(a.get(),"save");
            assertTrue(save.isEnabled());
            StationMapView map=ReflectionHelpers.getField(a.get(),"map");map.setFocusedRadius(500_000);
            save.performClick();assertTrue(a.get().isFinishing());
            assertEquals(500_000,LastTrainStore.notificationArea(context).radius,0);
            assertNull(LastTrainStore.home(context));
        }
    }
    @Test public void tabSelectionSurvivesRecreation(){
        try(ActivityController<MainActivity> a=Robolectric.buildActivity(MainActivity.class).setup()){
            find(a.get().findViewById(android.R.id.content),"終電タブ").performClick();
            Bundle state=new Bundle();a.saveInstanceState(state);
            assertEquals(1,state.getInt("feature"));
            try(ActivityController<MainActivity> b=Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible()){
                assertTrue(find(b.get().findViewById(android.R.id.content),"終電タブ").isSelected());
            }
        }
    }
    private View find(View v,String name){
        if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),name);if(child!=null)return child;}
        return null;
    }
}
