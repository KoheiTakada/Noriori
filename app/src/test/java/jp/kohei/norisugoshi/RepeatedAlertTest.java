package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.location.LocationManager;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.util.Map;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class RepeatedAlertTest {
    private Context c;private Station station;
    @Before public void setup(){
        LastTrainFixtures.night();c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("night_reset_v1",0).edit().clear().commit();
        c.getSharedPreferences("monitor_state",0).edit().clear().putInt("state_schema",5).commit();LastTrainStore.prefs(c).edit().clear().commit();
        ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);AlertRepeater.stop(4301);AlertRepeater.stop(4500);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(c.getSystemService(LocationManager.class)).setLocationEnabled(true);
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
        PlaceFixtures.install(c);station=StationRepository.findById(c,StationRepository.ASAKADAI.id);
    }
    @After public void finish(){
        AlertRepeater.stop(4301);AlertRepeater.stop(4500);MonitorStateStore.clearActive(c);LastTrainStore.alerting(c,false);
        AlarmVibration.refresh(c);LastTrainFixtures.reset();
    }
    private Notification notification(int id){return Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getNotification(id);}
    private void advance(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
    private ServiceController<TravelMonitorService> arrival(){
        MonitorStateStore.setArmed(c,station,true);
        ServiceController<TravelMonitorService> s=Robolectric.buildService(TravelMonitorService.class).create();
        s.get().onStartCommand(new Intent(c,TravelMonitorService.class).setAction(TravelMonitorService.ACTION_ARRIVAL_ALERT)
                .putExtra(TravelMonitorService.EXTRA_STATION_ID,station.id),0,1);return s;
    }
    private ServiceController<LastTrainAlertService> lastTrain(){
        long now=LastTrainEngine.now();LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.route(c,new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",now+5*60_000L,now+35*60_000L,now,35.73,139.71,false));
        LastTrainStore.prefs(c).edit().putString("zone","notify").commit();LastTrainStore.alerting(c,true);
        ServiceController<LastTrainAlertService> s=Robolectric.buildService(LastTrainAlertService.class).create();s.get().onStartCommand(new Intent(),0,1);return s;
    }
    @Test public void arrivalRepostsTheSameSlotAfterSwipeAndStopCancelsEveryFuturePost()throws Exception{
        ServiceController<TravelMonitorService> s=arrival();try{
            Notification first=notification(4301);assertNotNull(first);assertEquals(0,first.flags&Notification.FLAG_ONLY_ALERT_ONCE);
            c.getSystemService(NotificationManager.class).cancel(4301);assertNull(notification(4301));
            advance(1_200);assertNotNull(notification(4301));assertNotSame(first,notification(4301));
            advance(6_000);assertEquals(1,Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getAllNotifications().size());
            notification(4301).actions[0].actionIntent.send();Intent stop=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(stop);s.get().onStartCommand(stop,0,2);advance(5_000);
            assertNull(notification(4301));assertEquals(MonitorStateStore.MODE_NONE,MonitorStateStore.getActiveMode(c));
            assertFalse(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
        }finally{s.destroy();}
    }
    @Test public void vibrationEndsBetweenFinitePulsesAndRepeatsWithTheNextNotification(){
        ServiceController<TravelMonitorService> s=arrival();try{
            android.os.Vibrator v=c.getSystemService(VibratorManager.class).getDefaultVibrator();assertTrue(Shadows.shadowOf(v).isVibrating());
            advance(1_001);assertFalse(Shadows.shadowOf(v).isVibrating());advance(199);assertTrue(Shadows.shadowOf(v).isVibrating());
            advance(1_001);assertFalse(Shadows.shadowOf(v).isVibrating());
        }finally{s.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_STOP_ALL),0,2);s.destroy();}
    }
    @Test public void lastTrainRepostsTheSameSlotAndItsStopButtonCancelsRepeats()throws Exception{
        ServiceController<LastTrainAlertService> s=lastTrain();try{
            Notification first=notification(4500);assertNotNull(first);c.getSystemService(NotificationManager.class).cancel(4500);
            advance(1_200);assertNotNull(notification(4500));assertEquals(0,notification(4500).flags&Notification.FLAG_ONLY_ALERT_ONCE);
            for(int i=0;i<4;i++)advance(1_200);assertEquals(1,Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getAllNotifications().size());
            notification(4500).actions[0].actionIntent.send();advance(5_000);
            assertFalse(LastTrainStore.alerting(c));assertTrue(LastTrainStore.muted(c,LastTrainEngine.now()));assertNull(notification(4500));
        }finally{s.destroy();}
    }
    @Test public void duplicateStartsKeepOneTimerAndStoppingReleasesTheWakeLock(){
        ServiceController<LastTrainAlertService> s=lastTrain();try{
            Map<Integer,?> active=ReflectionHelpers.getStaticField(AlertRepeater.class,"active");Object before=active.get(4500);
            PowerManager.WakeLock wake=ReflectionHelpers.getField(before,"wake");assertTrue(wake.isHeld());
            s.get().onStartCommand(new Intent(),0,2);assertSame(before,active.get(4500));
            LastTrainCoordinator.stopActive(c,true);assertFalse(wake.isHeld());advance(5_000);assertNull(notification(4500));
        }finally{s.destroy();}
    }
    @Test public void stoppingLastTrainKeepsAnArrivalRepeatingInItsOwnSingleSlot(){
        ServiceController<TravelMonitorService> arrival=arrival();ServiceController<LastTrainAlertService> train=lastTrain();try{
            LastTrainCoordinator.stopActive(c,true);c.getSystemService(NotificationManager.class).cancel(4301);advance(1_200);
            assertNotNull(notification(4301));assertNull(notification(4500));assertEquals(MonitorStateStore.MODE_ALERTING,MonitorStateStore.getActiveMode(c));
            arrival.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_STOP_ALL),0,2);advance(5_000);
            assertTrue(Shadows.shadowOf(c.getSystemService(NotificationManager.class)).getAllNotifications().isEmpty());
        }finally{arrival.destroy();train.destroy();}
    }
    @Test public void disabledChannelStopsTheAlarmInsteadOfKeepingANotificationlessStatus(){
        ServiceController<LastTrainAlertService> s=lastTrain();try{
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            manager.deleteNotificationChannel(LastTrainAlertService.CHANNEL);
            manager.createNotificationChannel(new NotificationChannel(LastTrainAlertService.CHANNEL,"終電前の通知",NotificationManager.IMPORTANCE_NONE));
            advance(1_200);assertFalse(LastTrainStore.alerting(c));assertNull(notification(4500));
        }finally{s.destroy();}
    }
    @Test public void redeliveredArrivalRestoresRepeatsEvenThoughTheStationIsAlreadyDisarmed(){
        ServiceController<TravelMonitorService> first=arrival();first.destroy();c.getSystemService(NotificationManager.class).cancel(4301);
        assertFalse(MonitorStateStore.isArmed(c,station));
        ServiceController<TravelMonitorService> second=Robolectric.buildService(TravelMonitorService.class).create();try{
            second.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_ARRIVAL_ALERT)
                    .putExtra(TravelMonitorService.EXTRA_STATION_ID,station.id),0,2);
            assertNotNull(notification(4301));c.getSystemService(NotificationManager.class).cancel(4301);advance(1_200);assertNotNull(notification(4301));
            second.get().onStartCommand(new Intent().setAction(TravelMonitorService.ACTION_STOP_ALL),0,3);
        }finally{second.destroy();}
    }
}
