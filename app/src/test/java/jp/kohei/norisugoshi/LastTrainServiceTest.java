package jp.kohei.norisugoshi;
import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29) @LooperMode(LooperMode.Mode.PAUSED)
public class LastTrainServiceTest {
    private Context context;
    @After public void restoreClock(){LastTrainFixtures.reset();}
    @Before public void reset(){LastTrainFixtures.night();context=RuntimeEnvironment.getApplication();LastTrainStore.prefs(context).edit().clear().commit();
        MonitorStateStore.clearActive(context);ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);}
    private void route(){long now=LastTrainEngine.now();
        LastTrainStore.saveHome(context,new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.route(context,new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",now+10*60_000L,now+40*60_000L,now,35.73,139.71,false));
        LastTrainStore.prefs(context).edit().putString("zone","notify").putLong("fixAt",now).commit();}
    @Test public void continuousAlarmStopClearsNotificationAndCancelsReservation(){
        route();LastTrainStore.alerting(context,true);
        ServiceController<LastTrainAlertService>s=Robolectric.buildService(LastTrainAlertService.class).create();try{
            assertEquals(Service.START_REDELIVER_INTENT,s.get().onStartCommand(new Intent(),0,1));
            NotificationManager nm=context.getSystemService(NotificationManager.class);
            assertNotNull(Shadows.shadowOf(nm).getNotification(4500));
            LastTrainCoordinator.stopActive(context,true);
            assertNull(Shadows.shadowOf(nm).getNotification(4500));assertFalse(LastTrainStore.alerting(context));assertTrue(LastTrainStore.muted(context,LastTrainEngine.now()));
            LastTrainCoordinator.restore(context);assertFalse(LastTrainStore.alerting(context));
            assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
        }finally{s.destroy();}
    }
    @Test public void missingRouteCannotStartVibration(){
        ServiceController<LastTrainAlertService>s=Robolectric.buildService(LastTrainAlertService.class).create();try{
            assertEquals(Service.START_NOT_STICKY,s.get().onStartCommand(new Intent(),0,1));assertFalse(LastTrainStore.alerting(context));
        }finally{s.destroy();}
    }
    @Test public void suppressingReturnAlertKeepsArrivalAlertVibrating(){
        route();LastTrainStore.alerting(context,true);
        MonitorStateStore.setActive(context,MonitorStateStore.MODE_ALERTING,StationRepository.IKEBUKURO);
        AlarmVibration.refresh(context);LastTrainCoordinator.stopActive(context,false);
        assertTrue(Shadows.shadowOf(context.getSystemService(Vibrator.class)).isVibrating());
        MonitorStateStore.clearActive(context);AlarmVibration.refresh(context);
        assertFalse(Shadows.shadowOf(context.getSystemService(Vibrator.class)).isVibrating());
    }
}
