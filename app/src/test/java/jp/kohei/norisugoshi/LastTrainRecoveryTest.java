package jp.kohei.norisugoshi;
import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
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
public class LastTrainRecoveryTest {
    private Context c;private long now;
    @After public void restoreClock(){LastTrainFixtures.reset();}
    @Before public void reset(){LastTrainFixtures.night();
        c=RuntimeEnvironment.getApplication();now=LastTrainEngine.now();
        LastTrainStore.prefs(c).edit().clear().commit();MonitorStateStore.clearActive(c);
        ReflectionHelpers.setStaticField(LastTrainCoordinator.class,"registered",-1L);
        ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.prefs(c).edit().putString("zone","notify").commit();
    }
    private int routeAlarmCount(){int count=0;for(ShadowAlarmManager.ScheduledAlarm a:Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms())
        if(!"session".equals(Shadows.shadowOf(a.operation).getSavedIntent().getAction())&&!NightReset.ACTION.equals(Shadows.shadowOf(a.operation).getSavedIntent().getAction()))count++;return count;}
    private LastTrainRoute route(long leave){return new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",leave,leave+30*60_000L,now,35.73,139.71,false);}
    private void changedPosition(){LastTrainStore.prefs(c).edit().putString("fixLat","35.90").putString("fixLon","139.50").putLong("fixAt",now).commit();}
    @Test public void rebootRestoresSavedAlarmWithoutAnyLocationPermission(){
        LastTrainRoute r=route(now+60*60_000L);LastTrainStore.route(c,r);
        assertFalse(PermissionUtils.hasFineLocation(c));assertFalse(PermissionUtils.hasBackgroundLocation(c));
        new BootReceiver().onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(2,routeAlarmCount());
        assertEquals(r.alarmAt(15)-5*60_000L,Shadows.shadowOf(c.getSystemService(AlarmManager.class)).peekNextScheduledAlarm().triggerAtTime);
    }
    @Test public void cancelledRequestCannotRestoreRouteOrShowAnErrorAfterStop(){
        long generation=LastTrainStore.generation(c);LastTrainCoordinator.stopActive(c,true);
        LastTrainCoordinator.completeSearch(c,generation,route(now+60*60_000L),null);
        LastTrainCoordinator.completeSearch(c,generation,null,new IOException("offline"));
        assertNull(LastTrainStore.route(c));assertEquals("",LastTrainStore.problem(c));
        assertEquals(0,routeAlarmCount());
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
    }
    @Test public void resultFromPreviousPositionDoesNotStartAnAlarmAtNewPosition(){
        changedPosition();LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),route(now+5*60_000L),null);
        assertNull(LastTrainStore.route(c));assertFalse(LastTrainStore.alerting(c));
    }
    @Test public void responseFromDeletedOrChangedHomeIsDiscarded(){
        long generation=LastTrainStore.generation(c);LastTrainStore.deleteHome(c);
        LastTrainCoordinator.completeSearch(c,generation,route(now+5*60_000L),null);
        assertNull(LastTrainStore.route(c));assertFalse(LastTrainStore.alerting(c));
    }
    @Test public void homeAndTravelSuppressPersistedActiveAlarmDuringRestore(){
        for(String zone:new String[]{"home","travel"}){
            LastTrainStore.route(c,route(now+5*60_000L));LastTrainStore.alerting(c,true);
            LastTrainStore.prefs(c).edit().putString("zone",zone).commit();
            LastTrainCoordinator.restore(c);assertFalse(LastTrainStore.alerting(c));
            assertEquals(0,routeAlarmCount());
        }
    }
    @Test public void deletingHomeCancelsReservationAndClearsActiveFlag(){
        LastTrainRoute r=route(now+60*60_000L);LastTrainStore.route(c,r);LastTrainAlarms.schedule(c,r);LastTrainStore.alerting(c,true);
        LastTrainStore.deleteHome(c);LastTrainCoordinator.configure(c);
        assertFalse(LastTrainStore.alerting(c));assertNull(LastTrainStore.home(c));
        assertEquals(0,routeAlarmCount());
    }
    @Test public void movedOriginWithFailedSearchIsUnconfirmedAndDoesNotConsumeNight(){
        LastTrainRoute r=route(now-1);LastTrainStore.route(c,r);changedPosition();
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("offline"));
        assertEquals("終電を確認できない",LastTrainStore.problem(c));assertFalse(LastTrainStore.announced(c,r));
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
        LastTrainRoute next=new LastTrainRoute(r.day,r.station,r.path,now+5*60_000L,now+40*60_000L,now,35.9,139.5,false);
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),next,null);assertTrue(LastTrainStore.alerting(c));
    }
    @Test public void failedRefreshKeepsSameOriginReservation(){
        LastTrainRoute r=route(now+60*60_000L);LastTrainStore.route(c,r);LastTrainAlarms.schedule(c,r);
        LastTrainCoordinator.completeSearch(c,LastTrainStore.generation(c),null,new IOException("offline"));
        assertEquals(r.leaveAt,LastTrainStore.route(c).leaveAt);
        assertEquals(2,routeAlarmCount());
        assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4506));
    }
    @Test public void deniedExactAlarmPermissionCreatesNoReservationAndGrantRestoresIt(){
        LastTrainStore.route(c,route(now+60*60_000L));ShadowAlarmManager.setCanScheduleExactAlarms(false);
        LastTrainCoordinator.configure(c);assertEquals("アラームを許可",LastTrainStore.problem(c));
        assertEquals(0,routeAlarmCount());
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        new LastTrainReceiver().onReceive(c,new Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED));
        assertEquals(2,routeAlarmCount());
    }
    @Test @Config(shadows=RevokedDuringSchedule.class) public void permissionRevokedBetweenCheckAndSchedulingDoesNotCrash(){
        LastTrainAlarms.schedule(c,route(now+60*60_000L));assertEquals("アラームを許可",LastTrainStore.problem(c));
        assertEquals(0,routeAlarmCount());
    }
    @Implements(AlarmManager.class) public static class RevokedDuringSchedule extends ShadowAlarmManager {
        @Implementation protected void setExactAndAllowWhileIdle(int type,long trigger,PendingIntent operation){throw new SecurityException("Revoked after permission check");}
    }
    @Test public void schedulingAcrossAlarmInstantStillCreatesImmediateWakeup(){
        LastTrainAlarms.schedule(c,route(now+14*60_000L));
        assertFalse(Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
        long at=Shadows.shadowOf(c.getSystemService(AlarmManager.class)).peekNextScheduledAlarm().triggerAtTime;
        assertTrue(at>=now);assertTrue(at-now<5_000);
    }
    @Test public void specialUseAlarmStartsAndStopReceiverClearsNotificationAndVibration(){
        LastTrainStore.route(c,route(now+5*60_000L));LastTrainStore.alerting(c,true);
        ServiceController<LastTrainAlertService>s=Robolectric.buildService(LastTrainAlertService.class).create();try{
            assertEquals(Service.START_REDELIVER_INTENT,s.get().onStartCommand(new Intent(),0,1));
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,s.get().getForegroundServiceType());
            assertNotNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4500));
            assertTrue(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
            new LastTrainReceiver().onReceive(c,new Intent("stop"));assertFalse(LastTrainStore.alerting(c));
            assertNull(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4500));
            assertFalse(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
            new BootReceiver().onReceive(c,new Intent(Intent.ACTION_BOOT_COMPLETED));assertFalse(LastTrainStore.alerting(c));
        }finally{s.destroy();}
    }
    @Test public void freshFixCannotHideThatAlarmRouteWasFromAnOlderPosition(){
        changedPosition();LastTrainRoute r=route(now+5*60_000L);LastTrainStore.route(c,r);LastTrainStore.alerting(c,true);
        ServiceController<LastTrainAlertService>s=Robolectric.buildService(LastTrainAlertService.class).create();try{
            s.get().onStartCommand(new Intent(),0,1);
            Notification n=Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(4500);
            assertTrue(n.extras.getString(Notification.EXTRA_TEXT).contains(LastTrainEngine.time(r.fetchedAt)+"の位置"));
        }finally{LastTrainCoordinator.stopActive(c,true);s.destroy();}
    }
    @Test public void backwardClockChangeRequiresNewSearchInsteadOfReusingFutureResult(){
        assertFalse(LastTrainEngine.reusable(route(now+60*60_000L),35.73,139.71,now-1));
    }
}
