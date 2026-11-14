package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.location.Location;
import android.os.VibratorManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LastTrainSilentRecoveryTest {
    private Context c;
    private long now;
    @Before public void reset(){
        now=LastTrainFixtures.night();c=RuntimeEnvironment.getApplication();
        LastTrainStore.prefs(c).edit().clear().commit();MonitorStateStore.clearActive(c);
        ReflectionHelpers.setStaticField(LastTrainCoordinator.class,"registered",-1L);
        ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.prefs(c).edit().putString("zone","notify").commit();
    }
    @After public void finish(){
        MonitorStateStore.clearActive(c);LastTrainCoordinator.stopActive(c,false);LastTrainFixtures.reset();
    }
    private Location fix(double latitude,double longitude){
        Location l=new Location("test");l.setLatitude(latitude);l.setLongitude(longitude);l.setAccuracy(10);l.setTime(now);return l;
    }
    private LastTrainRoute route(double latitude,double longitude,long leaveAt){
        return new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",leaveAt,leaveAt+30*60_000L,now,latitude,longitude,false);
    }
    private Notification notice(int id){return Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(id);}
    private void fail(){LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("network recovering"));}
    private void post(int id,String title){
        LastTrainAlertService.channels(c);
        c.getSystemService(NotificationManager.class).notify(id,new Notification.Builder(c,LastTrainAlertService.CHANNEL)
                .setSmallIcon(R.drawable.ic_noriori).setContentTitle(title).build());
    }
    private long alarmTime(){
        for(ShadowAlarmManager.ScheduledAlarm a:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms())
            if("alarm".equals(Shadows.shadowOf(a.operation).getSavedIntent().getAction()))return a.triggerAtTime;
        return -1;
    }
    @Test public void freshFixAfterBlindSpotQueuesLookupButFailureDoesNotPush(){
        LastTrainStore.prefs(c).edit().putLong("fixAt",now-30*60_000L).commit();
        LastTrainCoordinator.observe(c,fix(35.73,139.71));
        assertNotNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
        fail();assertEquals("終電を確認できない",LastTrainStore.problem(c));assertNull(notice(4506));
        assertTrue(LastTrainStore.prefs(c).getLong("retryAt",0)>now);assertFalse(LastTrainStore.alerting(c));
    }
    @Test public void repeatedSurfaceFailuresStaySilentEvenAfterSuccessfulRecoveries(){
        long start=now;
        for(int i=0;i<3;i++){
            now=start+i*20*60_000L;LastTrainFixtures.set(now);double lat=35.73+i*.01;
            LastTrainSearchJob.cancel(c);LastTrainCoordinator.observe(c,fix(lat,139.71));fail();
            assertNull(notice(4506));assertFalse(LastTrainStore.prefs(c).getBoolean("errorNotified",false));
            LastTrainRoute r=route(lat,139.71,now+120*60_000L);
            LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),r,null);
            assertEquals("",LastTrainStore.problem(c));assertEquals(r.leaveAt,LastTrainStore.route(c).leaveAt);
            assertNull(notice(4506));assertEquals(r.alarmAt(15),alarmTime());
        }
    }
    @Test public void failurePreservesDepartureReservationAndAnArrivalAlarm(){
        LastTrainCoordinator.observe(c,fix(35.73,139.71));LastTrainRoute r=route(35.73,139.71,now+60*60_000L);
        LastTrainStore.route(c,r);LastTrainAlarms.schedule(c,r);long before=alarmTime();assertTrue(before>now);
        MonitorStateStore.setActive(c,MonitorStateStore.MODE_ALERTING,StationRepository.IKEBUKURO);AlarmVibration.refresh(c);
        fail();assertEquals(before,alarmTime());assertEquals(r.leaveAt,LastTrainStore.route(c).leaveAt);assertNull(notice(4506));
        assertEquals(MonitorStateStore.MODE_ALERTING,MonitorStateStore.getActiveMode(c));
        assertTrue(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
    }
    @Test public void oldOriginPastDeadlineDoesNotPushAnUnconfirmedResult(){
        LastTrainRoute old=route(35.73,139.71,now-1);LastTrainStore.route(c,old);
        LastTrainStore.prefs(c).edit().putString("fixLat","35.90").putString("fixLon","139.50").putLong("fixAt",now).commit();
        new LastTrainReceiver().onReceive(c,new Intent("alarm"));
        assertEquals("終電を確認できない",LastTrainStore.problem(c));assertNull(notice(4506));
        assertFalse(LastTrainStore.announced(c,old));assertFalse(LastTrainStore.alerting(c));
    }
    @Test public void configurationRemovesAnOlderErrorButKeepsAnAlarmNotice(){
        LastTrainRoute r=route(35.73,139.71,now+60*60_000L);LastTrainStore.route(c,r);
        post(4506,"終電を確認できない");post(4500,"22:00までに出発");post(9999,"終電を確認できない");
        LastTrainStore.prefs(c).edit().putBoolean("errorNotified",true).commit();LastTrainCoordinator.configure(c);
        assertNull(notice(4506));assertNotNull(notice(4500));assertNotNull(notice(9999));
        assertFalse(LastTrainStore.prefs(c).contains("errorNotified"));assertEquals(r.leaveAt,LastTrainStore.route(c).leaveAt);
    }
    @Test public void configurationKeepsAConfirmedMissedDepartureNotice(){
        LastTrainRoute r=route(35.73,139.71,now-1);LastTrainStore.route(c,r);LastTrainStore.markAnnounced(c,r);
        LastTrainAlertService.missedNotice(c);assertNotNull(notice(4506));
        LastTrainStore.prefs(c).edit().putBoolean("errorNotified",true).commit();LastTrainCoordinator.configure(c);
        assertNotNull(notice(4506));assertEquals("間に合う終電なし",notice(4506).extras.getString(Notification.EXTRA_TITLE));
        assertTrue(LastTrainStore.announced(c,r));assertFalse(LastTrainStore.prefs(c).contains("errorNotified"));
    }
    @Test public void confirmedDepartureStillVibratesAfterAnEarlierSilentFailure(){
        LastTrainCoordinator.observe(c,fix(35.73,139.71));fail();assertNull(notice(4506));
        LastTrainRoute r=route(35.73,139.71,now+5*60_000L);
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),r,null);assertTrue(LastTrainStore.alerting(c));
        ServiceController<LastTrainAlertService> service=Robolectric.buildService(LastTrainAlertService.class).create();
        try{
            assertEquals(Service.START_REDELIVER_INTENT,service.get().onStartCommand(new Intent(),0,1));
            assertNotNull(notice(4500));assertNull(notice(4506));
            assertTrue(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
            LastTrainCoordinator.stopActive(c,true);assertNull(notice(4500));
            assertFalse(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
        }finally{service.destroy();}
    }
}
