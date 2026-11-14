package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.location.Location;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowJobService;
import org.robolectric.util.ReflectionHelpers;
import java.time.*;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LastTrainQuietDayTest {
    Context c;
    long at(String time){return OffsetDateTime.parse("2026-10-13T"+time+"+09:00").toInstant().toEpochMilli();}
    void clock(long now){ReflectionHelpers.setStaticField(LastTrainEngine.class,"clock",Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));}
    LastTrainRoute route(long now,long leave){return new LastTrainRoute(LastTrainEngine.day(now),"池袋","山手線",leave,leave+30*60_000L,now,35.73,139.71,false);}
    @Before public void reset(){
        c=RuntimeEnvironment.getApplication();LastTrainStore.prefs(c).edit().clear().commit();clock(at("09:54:00"));
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,Manifest.permission.POST_NOTIFICATIONS);
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
        LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.prefs(c).edit().putString("zone","notify").putString("fixLat","35.73").putString("fixLon","139.71").putLong("fixAt",at("09:54:00")).commit();
    }
    @After public void restoreClock(){ReflectionHelpers.setStaticField(LastTrainEngine.class,"clock",Clock.systemUTC());}
    @Test public void morningFixDoesNotQueueAnyLookup(){
        Location fix=new Location("test");fix.setLatitude(35.73);fix.setLongitude(139.71);fix.setAccuracy(10);fix.setTime(at("09:54:00"));
        LastTrainCoordinator.observe(c,fix);
        JobInfo job=c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID);
        assertNull(job);
        assertFalse(LastTrainCoordinator.watchAllowed(c));
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4515));
        Intent started=Shadows.shadowOf(RuntimeEnvironment.getApplication()).peekNextStartedService();
        assertTrue(started==null||!started.getComponent().getClassName().equals(LastTrainWatchService.class.getName()));
    }
    @Test public void morningFailureCreatesNoErrorNotificationOrConsumedNightFlag(){
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("offline"));
        assertEquals("",LastTrainStore.problem(c));
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
        assertFalse(LastTrainStore.prefs(c).getBoolean("errorNotified",false));
    }
    void runOfflineJob() throws Exception {
        ServiceController<LastTrainSearchJob> service=Robolectric.buildService(LastTrainSearchJob.class).create();
        JobParameters params=Shadow.newInstanceOf(JobParameters.class);
        try{
            assertTrue(service.get().onStartJob(params));
            ShadowJobService shadow=Shadow.extract(service.get());
            long until=System.nanoTime()+5_000_000_000L;
            while(!shadow.getIsJobFinished()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5);}
            assertTrue(shadow.getIsJobFinished());assertTrue(shadow.getIsRescheduleNeeded());
            assertEquals("終電を確認できない",LastTrainStore.problem(c));
        }finally{service.get().onStopJob(params);service.destroy();}
    }
    @Test public void offlineMorningJobIsRejectedWithoutStartingWork() {
        ServiceController<LastTrainSearchJob> service=Robolectric.buildService(LastTrainSearchJob.class).create();
        try{
            assertFalse(service.get().onStartJob(Shadow.newInstanceOf(JobParameters.class)));
            assertFalse(service.get().onStopJob(Shadow.newInstanceOf(JobParameters.class)));
            assertEquals("",LastTrainStore.problem(c));
        }finally{service.destroy();}
    }
    @Test public void offlineEveningJobRetriesWithoutAnErrorNotification() throws Exception {
        clock(at("21:00:00"));LastTrainStore.prefs(c).edit().putLong("fixAt",LastTrainEngine.now()).commit();runOfflineJob();
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
    }
    @Test public void eveningFailureStaysSilentAndSuccessClearsTheProblem(){
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("offline"));clock(at("21:00:00"));
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("offline"));
        assertEquals("終電を確認できない",LastTrainStore.problem(c));
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),route(LastTrainEngine.now(),at("23:30:00")),null);
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
        assertEquals("",LastTrainStore.problem(c));
    }
    @Test public void oldWatchCannotReappearOnMorningServiceRestart(){
        LastTrainStore.route(c,route(at("09:54:00"),at("23:30:00")));
        ServiceController<LastTrainWatchService> service=Robolectric.buildService(LastTrainWatchService.class).create();try{
            assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(new Intent(),0,1));
            assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4515));
        }finally{service.destroy();}
    }
    @Test public void daytimeLateSearchResultIsDiscardedWithoutReservation(){
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),route(at("09:54:00"),at("23:30:00")),null);
        assertNull(LastTrainStore.route(c));assertFalse(LastTrainCoordinator.watchAllowed(c));
        assertTrue(Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
    }
    @Test public void nightSearchStillReservesAlarmAndWatchNearDeadline(){
        clock(at("18:00:00"));LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),route(LastTrainEngine.now(),at("23:30:00")),null);
        boolean watch=false,alarm=false;
        for(org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm scheduled:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms()){
            String action=Shadows.shadowOf(scheduled.operation).getSavedIntent().getAction();
            if("watch".equals(action)){watch=true;assertEquals(at("21:30:00"),scheduled.triggerAtTime);}
            if("alarm".equals(action)){alarm=true;assertEquals(at("23:15:00"),scheduled.triggerAtTime);}
        }assertTrue(watch);assertTrue(alarm);
    }
    @Test public void genuineEarlyLastTrainStillStartsWatchAndRingsBefore23(){
        clock(at("19:00:00"));LastTrainRoute route=route(LastTrainEngine.now(),at("20:30:00"));LastTrainStore.route(c,route);
        assertTrue(LastTrainCoordinator.watchAllowed(c));clock(at("20:15:00"));LastTrainCoordinator.evaluate(c);
        assertTrue(LastTrainStore.alerting(c));
    }
    @Test public void continuousCheckStopsAtDeadlineEvenWithoutFurtherGps(){
        clock(at("22:00:00"));LastTrainRoute route=route(LastTrainEngine.now(),at("23:30:00"));LastTrainStore.route(c,route);
        ServiceController<LastTrainWatchService> service=Robolectric.buildService(LastTrainWatchService.class).create();try{
            assertEquals(Service.START_STICKY,service.get().onStartCommand(new Intent(),0,1));
            assertNotNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4515));
            new LastTrainReceiver().onReceive(c,new Intent("watch_end"));
            assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4515));
        }finally{service.destroy();}
    }
    @Test public void stoppingCancelsPendingSilentLookupAndWatchAlarms(){
        clock(at("21:00:00"));
        LastTrainSearchJob.enqueue(c);LastTrainAlarms.schedule(c,route(LastTrainEngine.now(),at("23:30:00")));
        LastTrainCoordinator.stopActive(c,true);
        assertNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
        assertTrue(Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
    }
    @Test public void midnightRouteKeepsPriorNightWithoutMorningCheck(){
        long evening=at("23:00:00"),leave=OffsetDateTime.parse("2026-10-14T00:30:00+09:00").toInstant().toEpochMilli();
        LastTrainRoute route=route(evening,leave);
        assertTrue(LastTrainEngine.watchNeeded(route,evening));
        assertTrue(LastTrainEngine.watchNeeded(route,leave-15*60_000L));
        assertFalse(LastTrainEngine.watchNeeded(route,leave+1));
        assertFalse(LastTrainEngine.watchNeeded(route,at("09:54:00")));
    }
    @Test public void morningConfigurationOnlySchedulesEveningStart(){
        ReflectionHelpers.setStaticField(LastTrainCoordinator.class,"registered",-1L);
        LastTrainCoordinator.configure(c);
        assertEquals(-1L,(long)ReflectionHelpers.getStaticField(LastTrainCoordinator.class,"registered"));
        assertNull(ReflectionHelpers.getStaticField(LastTrainCoordinator.class,"currentFix"));
        assertNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
        java.util.List<org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm> alarms=Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms();
        assertEquals(2,alarms.size());
        for(org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm:alarms){
            String action=Shadows.shadowOf(alarm.operation).getSavedIntent().getAction();
            if("session".equals(action))assertEquals(at("18:00:00"),alarm.triggerAtTime);
            else{assertEquals(NightReset.ACTION,action);assertEquals(OffsetDateTime.parse("2026-10-14T04:00:00+09:00").toInstant().toEpochMilli(),alarm.triggerAtTime);}
        }
    }
    @Test public void eighteenStartsLocationAndReservesMorningShutdown(){
        clock(at("17:59:59"));LastTrainCoordinator.configure(c);
        assertNull(ReflectionHelpers.getStaticField(LastTrainCoordinator.class,"currentFix"));
        clock(at("18:00:00"));new LastTrainReceiver().onReceive(c,new Intent("session"));
        assertNotNull(ReflectionHelpers.getStaticField(LastTrainCoordinator.class,"currentFix"));
        boolean shutdown=false;
        for(org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms())
            if("session".equals(Shadows.shadowOf(alarm.operation).getSavedIntent().getAction())){
                shutdown=true;assertEquals(OffsetDateTime.parse("2026-10-14T04:00:00+09:00").toInstant().toEpochMilli(),alarm.triggerAtTime);
            }
        assertTrue(shutdown);
    }
    @Test public void fourCancelsJobFixAndBothAlarms(){
        clock(at("03:59:00"));LastTrainCoordinator.configure(c);LastTrainSearchJob.enqueue(c);
        com.google.android.gms.tasks.CancellationTokenSource fix=ReflectionHelpers.getStaticField(LastTrainCoordinator.class,"currentFix");
        LastTrainStore.alerting(c,true);MonitorStateStore.setActive(c,MonitorStateStore.MODE_ALERTING,StationRepository.IKEBUKURO);
        clock(at("04:00:00"));new LastTrainReceiver().onReceive(c,new Intent("session"));
        assertTrue(fix.getToken().isCancellationRequested());assertFalse(LastTrainStore.alerting(c));
        assertNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
        assertEquals(MonitorStateStore.MODE_NONE,MonitorStateStore.getActiveMode(c));
        for(org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm alarm:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms()){
            String action=Shadows.shadowOf(alarm.operation).getSavedIntent().getAction();
            if("session".equals(action))assertEquals(at("18:00:00"),alarm.triggerAtTime);
            else{assertEquals(NightReset.ACTION,action);assertEquals(OffsetDateTime.parse("2026-10-14T04:00:00+09:00").toInstant().toEpochMilli(),alarm.triggerAtTime);}
        }
        MonitorStateStore.clearActive(c);AlarmVibration.refresh(c);
    }
    @Test public void responseAfterFourCannotSaveRouteOrShowFailure(){
        clock(at("03:59:00"));long generation=LastTrainStore.generation(c);LastTrainRoute r=route(LastTrainEngine.now(),at("04:01:00"));
        clock(at("04:00:00"));LastTrainCoordinator.completeSearch(c,generation,r,null);
        LastTrainCoordinator.completeSearch(c,generation,null,new IOException("offline"));
        assertNull(LastTrainStore.route(c));assertEquals("",LastTrainStore.problem(c));assertFalse(LastTrainStore.alerting(c));
    }
    @Test public void oldAlarmServiceCannotRestartInMorning(){
        LastTrainStore.route(c,route(LastTrainEngine.now(),at("10:00:00")));LastTrainStore.alerting(c,true);
        ServiceController<LastTrainAlertService> service=Robolectric.buildService(LastTrainAlertService.class).create();
        try{assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(new Intent(),0,1));
            assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4500));
        }finally{service.destroy();}
    }
    @Test public void timeWindowIncludesMidnightAndEndsAtFour(){
        assertFalse(LastTrainEngine.activeTime(at("17:59:59")));assertTrue(LastTrainEngine.activeTime(at("18:00:00")));
        assertTrue(LastTrainEngine.activeTime(at("00:00:00")));assertTrue(LastTrainEngine.activeTime(at("03:59:59")));
        assertFalse(LastTrainEngine.activeTime(at("04:00:00")));assertFalse(LastTrainEngine.activeTime(at("09:54:00")));
    }

}
