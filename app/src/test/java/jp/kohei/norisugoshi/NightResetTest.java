package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.location.LocationManager;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.util.ReflectionHelpers;
import java.time.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class NightResetTest {
    private Context c;private Station station;
    private long at(String time){return OffsetDateTime.parse(time+"+09:00").toInstant().toEpochMilli();}
    private void clock(String time){LastTrainFixtures.set(at(time));}
    @Before public void setup(){
        c=RuntimeEnvironment.getApplication();clock("2026-10-14T03:59:00");
        c.getSharedPreferences("night_reset_v1",0).edit().clear().commit();
        c.getSharedPreferences("monitor_state",0).edit().clear().putInt("state_schema",5).commit();LastTrainStore.prefs(c).edit().clear().commit();
        AlertRepeater.stop(4301);AlertRepeater.stop(4500);ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);
        ReflectionHelpers.setStaticField(LastTrainCoordinator.class,"registered",-1L);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(c.getSystemService(LocationManager.class)).setLocationEnabled(true);ShadowAlarmManager.setCanScheduleExactAlarms(true);
        PlaceFixtures.install(c);station=StationRepository.findById(c,StationRepository.ASAKADAI.id);
    }
    @After public void finish(){
        AlertRepeater.stop(4301);AlertRepeater.stop(4500);MonitorStateStore.clearActive(c);LastTrainStore.alerting(c,false);AlarmVibration.refresh(c);LastTrainFixtures.reset();
    }
    private void home(){LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));}
    private LastTrainRoute route(){long now=LastTrainEngine.now();return new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",now+5*60_000L,now+35*60_000L,now,35.73,139.71,false);}
    private long scheduledReset(){
        for(ShadowAlarmManager.ScheduledAlarm alarm:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms())
            if(NightReset.ACTION.equals(Shadows.shadowOf(alarm.operation).getSavedIntent().getAction()))return alarm.triggerAtTime;
        return -1;
    }
    @Test public void nextResetStaysAt04TokyoAcrossMidnight(){
        assertEquals(at("2026-10-14T04:00:00"),NightReset.next(at("2026-10-13T23:59:00")));
        assertEquals(at("2026-10-14T04:00:00"),NightReset.next(at("2026-10-14T03:59:59")));
        assertEquals(at("2026-10-15T04:00:00"),NightReset.next(at("2026-10-14T04:00:00")));
        clock("2026-10-13T23:59:00");NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);
        clock("2026-10-14T00:01:00");assertFalse(NightReset.check(c));assertTrue(MonitorStateStore.isArmed(c,station));
    }
    @Test public void installingDuringATripDoesNotSwitchItOffImmediately(){
        MonitorStateStore.setArmed(c,station,true);NightReset.configure(c);assertTrue(MonitorStateStore.isArmed(c,station));
        assertEquals(at("2026-10-14T04:00:00"),scheduledReset());
    }
    @Test public void fourStopsBothRepeatersAndDisarmsEveryStationEvenWithoutAnAlarmBroadcast()throws Exception{
        home();String home=LastTrainStore.home(c).json().toString(),outer=LastTrainStore.notificationArea(c).json().toString();
        NightReset.configure(c);for(Station s:StationRepository.getAll(c))MonitorStateStore.setArmed(c,s,true);
        ServiceController<TravelMonitorService> arrival=Robolectric.buildService(TravelMonitorService.class).create();
        ServiceController<LastTrainAlertService> train=Robolectric.buildService(LastTrainAlertService.class).create();
        try{
            arrival.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_ARRIVAL_ALERT).putExtra(TravelMonitorService.EXTRA_STATION_ID,station.id),0,1);
            LastTrainStore.route(c,route());LastTrainStore.prefs(c).edit().putString("zone","notify").commit();LastTrainStore.alerting(c,true);
            LastTrainSearchJob.enqueue(c);train.get().onStartCommand(new Intent(),0,1);
            assertTrue(AlertRepeater.isRunning(4301));assertTrue(AlertRepeater.isRunning(4500));
            clock("2026-10-14T04:00:00");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200));
            for(Station s:StationRepository.getAll(c))assertFalse(MonitorStateStore.isArmed(c,s));
            assertEquals(MonitorStateStore.MODE_NONE,MonitorStateStore.getActiveMode(c));assertFalse(LastTrainStore.alerting(c));
            assertFalse(AlertRepeater.isRunning(4301));assertFalse(AlertRepeater.isRunning(4500));
            assertNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
            assertTrue(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getAllNotifications().isEmpty());
            assertFalse(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
            assertEquals(home,LastTrainStore.home(c).json().toString());assertEquals(outer,LastTrainStore.notificationArea(c).json().toString());
            assertEquals(at("2026-10-15T04:00:00"),scheduledReset());
        }finally{arrival.destroy();train.destroy();}
    }
    @Test public void replayingTheOldResetAfterMorningActivationDoesNotClearTheNewTrip(){
        NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);
        clock("2026-10-14T04:00:00");new LastTrainReceiver().onReceive(c,new Intent(NightReset.ACTION));assertFalse(MonitorStateStore.isArmed(c,station));
        clock("2026-10-14T05:00:00");MonitorStateStore.setArmed(c,station,true);
        new LastTrainReceiver().onReceive(c,new Intent(NightReset.ACTION));assertTrue(MonitorStateStore.isArmed(c,station));
    }
    @Test public void rebootAfterAMissedResetClearsOldStatesAndPreservesPlaces()throws Exception{
        home();LastTrainStore.minutes(c,30);String quiet=LastTrainStore.quietArea(c).json().toString();
        NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);MonitorStateStore.setActive(c,MonitorStateStore.MODE_ALERTING,station);
        LastTrainStore.route(c,route());LastTrainStore.alerting(c,true);
        clock("2026-10-14T09:00:00");new BootReceiver().onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertFalse(MonitorStateStore.isArmed(c,station));assertEquals(MonitorStateStore.MODE_NONE,MonitorStateStore.getActiveMode(c));
        assertFalse(LastTrainStore.alerting(c));assertNull(LastTrainStore.route(c));assertEquals(30,LastTrainStore.minutes(c));
        assertEquals(quiet,LastTrainStore.quietArea(c).json().toString());assertEquals(2,StationRepository.getConfiguredEnabled(c).size());
    }
    @Test public void arrivalRedeliveryAfterResetCannotRestoreThePreviousNight(){
        NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);MonitorStateStore.setActive(c,MonitorStateStore.MODE_ALERTING,station);
        clock("2026-10-14T04:00:00");NightReset.configure(c);clock("2026-10-14T05:00:00");
        ServiceController<TravelMonitorService> s=Robolectric.buildService(TravelMonitorService.class).create();try{
            assertEquals(Service.START_NOT_STICKY,s.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_ARRIVAL_ALERT)
                    .putExtra(TravelMonitorService.EXTRA_STATION_ID,station.id),0,1));
            assertEquals(MonitorStateStore.MODE_NONE,MonitorStateStore.getActiveMode(c));assertFalse(AlertRepeater.isRunning(4301));
        }finally{s.destroy();}
    }
    @Test public void arrivalOnlyConfigurationStillSchedulesAndRunsTheDailyReset(){
        LastTrainStore.deleteHome(c);NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);
        assertEquals(at("2026-10-14T04:00:00"),scheduledReset());
        clock("2026-10-14T04:00:00");new LastTrainReceiver().onReceive(c,new Intent(NightReset.ACTION));assertFalse(MonitorStateStore.isArmed(c,station));
    }
    @Test public void deniedExactAlarmPermissionFallsBackWithoutLosingTheReset(){
        ShadowAlarmManager.setCanScheduleExactAlarms(false);NightReset.configure(c);MonitorStateStore.setArmed(c,station,true);
        assertEquals(at("2026-10-14T04:00:00"),scheduledReset());
        clock("2026-10-14T04:00:00");NightReset.configure(c);assertFalse(MonitorStateStore.isArmed(c,station));
        assertEquals(at("2026-10-15T04:00:00"),scheduledReset());
    }
    @Test public void backwardClockChangeDoesNotLeaveAFutureBoundaryBlockingFreshPositions(){
        NightReset.configure(c);clock("2026-10-14T04:00:00");NightReset.configure(c);
        assertEquals(at("2026-10-14T04:00:00"),NightReset.lastResetAt(c));
        clock("2026-10-14T03:30:00");MonitorStateStore.setArmed(c,station,true);NightReset.configure(c);
        assertTrue(MonitorStateStore.isArmed(c,station));assertTrue(NightReset.lastResetAt(c)<=LastTrainEngine.now());
        assertEquals(at("2026-10-14T04:00:00"),scheduledReset());
    }
}
