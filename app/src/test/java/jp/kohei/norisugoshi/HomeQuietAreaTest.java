package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.os.*;
import android.view.*;
import android.widget.Button;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h892dp-xhdpi") @LooperMode(LooperMode.Mode.PAUSED)
public class HomeQuietAreaTest {
    private Context c;
    private final HomeStation home=new HomeStation("朝霞台駅","東武東上線",35.8,139.5);
    @Before public void reset(){
        LastTrainFixtures.night();c=RuntimeEnvironment.getApplication();
        LastTrainStore.prefs(c).edit().clear().commit();MonitorStateStore.clearActive(c);
        ReflectionHelpers.setStaticField(AlarmVibration.class,"running",false);
        ReflectionHelpers.setStaticField(LastTrainCoordinator.class,"registered",-1L);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }
    @After public void finish(){LastTrainCoordinator.stopActive(c,false);LastTrainFixtures.reset();}
    private double[] point(double meters){return Geo.destination(home.latitude,home.longitude,meters,90);}
    private ReturnArea quiet(double radius){return new ReturnArea(LastTrainStore.QUIET_AREA_ID,"通知しないエリア",home.latitude,home.longitude,radius);}
    private void observe(double meters){
        double[] p=point(meters);Location fix=new Location("test");fix.setLatitude(p[0]);fix.setLongitude(p[1]);
        fix.setAccuracy(10);fix.setTime(LastTrainEngine.now());LastTrainCoordinator.observe(c,fix);
    }
    private LastTrainRoute route(double meters){
        long now=LastTrainEngine.now();double[] p=point(meters);
        return new LastTrainRoute(LastTrainEngine.day(now),"池袋","池袋 → 朝霞台",now+60*60_000L,now+90*60_000L,now,p[0],p[1],false);
    }
    @Test public void upgradeAddsQuietCircleWithoutInvalidatingRouteOrOtherSettings() throws Exception {
        ReturnArea outer=new ReturnArea("notification-area","通知するエリア",35.5,139.7,200_000);
        LastTrainRoute r=route(3000);
        LastTrainStore.prefs(c).edit().putString("homeStation",home.json().toString())
                .putString("notificationArea",outer.json().toString()).putString("route",r.json().toString())
                .putString("zone","notify").putInt("minutes",30).putLong("generation",9).commit();
        assertNotNull(LastTrainStore.home(c));ReturnArea added=LastTrainStore.quietArea(c);
        assertEquals(home.latitude,added.latitude,0);assertEquals(home.longitude,added.longitude,0);assertEquals(500,added.radius,0);
        assertEquals(r.json().toString(),LastTrainStore.route(c).json().toString());
        assertEquals(outer.json().toString(),LastTrainStore.notificationArea(c).json().toString());
        assertEquals(30,LastTrainStore.minutes(c));assertEquals("notify",LastTrainStore.prefs(c).getString("zone",""));
        assertEquals(9,LastTrainStore.generation(c));LastTrainStore.home(c);assertEquals(9,LastTrainStore.generation(c));
    }
    @Test public void quietCircleCanMoveAwayFromDestinationAndDoesNotChangeTheOuterCircle() throws Exception {
        LastTrainStore.saveHome(c,home);String outer=LastTrainStore.notificationArea(c).json().toString();
        double[] house=point(1200);assertTrue(LastTrainStore.saveQuietArea(c,new ReturnArea("ignored","家",house[0],house[1],300)));
        ReturnArea saved=LastTrainStore.quietArea(c);assertEquals(LastTrainStore.QUIET_AREA_ID,saved.id);
        assertEquals(300,saved.radius,0);assertEquals(house[1],saved.longitude,0);
        assertEquals(home.json().toString(),LastTrainStore.home(c).json().toString());assertFalse(LastTrainStore.home(c).json().has("radius"));
        assertEquals(outer,LastTrainStore.notificationArea(c).json().toString());
        assertEquals("home",LastTrainEngine.zone(house[0],house[1],10,saved,LastTrainStore.notificationArea(c),"notify"));
        assertEquals("notify",LastTrainEngine.zone(home.latitude,home.longitude,10,saved,LastTrainStore.notificationArea(c),"home"));
    }
    @Test public void changingDestinationMovesTheQuietCircleAndKeepsItsChosenRadius() throws Exception {
        LastTrainStore.saveHome(c,home);LastTrainStore.saveQuietArea(c,quiet(2000));LastTrainStore.minutes(c,30);
        String outer=LastTrainStore.notificationArea(c).json().toString();
        HomeStation next=new HomeStation("池袋駅","山手線",35.73,139.71);assertTrue(LastTrainStore.saveHome(c,next));
        assertEquals(next.latitude,LastTrainStore.quietArea(c).latitude,0);assertEquals(next.longitude,LastTrainStore.quietArea(c).longitude,0);
        assertEquals(2000,LastTrainStore.quietArea(c).radius,0);assertEquals(30,LastTrainStore.minutes(c));
        assertEquals(outer,LastTrainStore.notificationArea(c).json().toString());
    }
    @Test public void selectingSameStationKeepsTheCustomQuietCenter(){
        LastTrainStore.saveHome(c,home);double[] house=point(1000);
        LastTrainStore.saveQuietArea(c,new ReturnArea("house","家",house[0],house[1],400));
        LastTrainStore.saveHome(c,home);assertEquals(house[0],LastTrainStore.quietArea(c).latitude,0);
        assertEquals(house[1],LastTrainStore.quietArea(c).longitude,0);assertEquals(400,LastTrainStore.quietArea(c).radius,0);
    }
    @Test public void deletingHomeAlsoRemovesQuietCircleAndIncompleteQuietEditorCannotSave() throws Exception {
        LastTrainStore.saveHome(c,home);String outer=LastTrainStore.notificationArea(c).json().toString();
        assertTrue(LastTrainStore.deleteHome(c));assertNull(LastTrainStore.quietArea(c));
        assertEquals(outer,LastTrainStore.notificationArea(c).json().toString());assertFalse(LastTrainStore.saveQuietArea(c,quiet(500)));
        try(ActivityController<ReturnAreaEditorActivity> a=Robolectric.buildActivity(ReturnAreaEditorActivity.class,
                new Intent(c,ReturnAreaEditorActivity.class).putExtra(ReturnAreaEditorActivity.EXTRA_QUIET,true)).create()){
            assertTrue(a.get().isFinishing());
        }
    }
    @Test public void customRadiusSuppressesBeyondThePreviousFixedHomeBoundary(){
        LastTrainStore.saveHome(c,home);LastTrainStore.saveQuietArea(c,quiet(2000));
        observe(1000);assertEquals("home",LastTrainStore.prefs(c).getString("zone",""));
        observe(3000);assertEquals("notify",LastTrainStore.prefs(c).getString("zone",""));
        observe(200_000);assertEquals("travel",LastTrainStore.prefs(c).getString("zone",""));
    }
    @Test public void quietWinsInsideAndUncertainEdgesKeepStateUnlessDefinitelyOutsideOuter(){
        ReturnArea q=quiet(2000),outer=new ReturnArea("notification-area","通知するエリア",home.latitude,home.longitude,10_000);
        assertEquals("home",LastTrainEngine.zone(home.latitude,home.longitude,10,q,outer,"notify"));
        double[] edge=point(2000);
        assertEquals("notify",LastTrainEngine.zone(edge[0],edge[1],100,q,outer,"notify"));
        assertEquals("home",LastTrainEngine.zone(edge[0],edge[1],100,q,outer,"home"));
        ReturnArea smallOuter=new ReturnArea("notification-area","通知するエリア",home.latitude,home.longitude,1000);
        assertEquals("travel",LastTrainEngine.zone(edge[0],edge[1],100,q,smallOuter,"notify"));
        assertEquals("home",LastTrainEngine.zone(home.latitude,home.longitude,10,q,smallOuter,"notify"));
        assertEquals("notify",LastTrainEngine.zone(edge[0],edge[1],Float.NaN,q,outer,"notify"));
    }
    @Test public void enteringQuietStopsAlarmVibrationReservationAndPendingSearch(){
        LastTrainStore.saveHome(c,home);LastTrainStore.saveQuietArea(c,quiet(2000));
        LastTrainRoute r=route(1000);LastTrainStore.route(c,r);LastTrainStore.prefs(c).edit().putString("zone","notify").commit();
        LastTrainAlarms.schedule(c,r);LastTrainSearchJob.enqueue(c);long generation=LastTrainStore.generation(c);
        assertNotNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));LastTrainStore.alerting(c,true);
        ServiceController<LastTrainAlertService> service=Robolectric.buildService(LastTrainAlertService.class).create();
        try{
            assertEquals(Service.START_REDELIVER_INTENT,service.get().onStartCommand(new Intent(),0,1));
            NotificationManager nm=c.getSystemService(NotificationManager.class);
            assertNotNull(Shadows.shadowOf(nm).getNotification(4500));
            observe(1000);assertEquals("home",LastTrainStore.prefs(c).getString("zone",""));assertFalse(LastTrainStore.alerting(c));
            assertNull(Shadows.shadowOf(nm).getNotification(4500));
            assertFalse(Shadows.shadowOf(c.getSystemService(VibratorManager.class).getDefaultVibrator()).isVibrating());
            assertTrue(Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
            assertNull(c.getSystemService(JobScheduler.class).getPendingJob(LastTrainSearchJob.ID));
            LastTrainCoordinator.completeSearch(c,generation,route(1000),null);assertFalse(LastTrainStore.alerting(c));
        }finally{service.destroy();}
    }
    @Test public void leavingQuietInsideOuterReenablesReservationsAfterReturningHome(){
        LastTrainStore.saveHome(c,home);LastTrainStore.saveQuietArea(c,quiet(2000));LastTrainRoute r=route(3000);
        LastTrainStore.route(c,r);LastTrainStore.mute(c,LastTrainEngine.now());LastTrainStore.markAnnounced(c,r);
        LastTrainStore.prefs(c).edit().putString("zone","notify").commit();observe(1000);
        assertFalse(LastTrainStore.muted(c,LastTrainEngine.now()));assertFalse(LastTrainStore.announced(c,r));
        observe(3000);assertEquals("notify",LastTrainStore.prefs(c).getString("zone",""));
        assertFalse(Shadows.shadowOf(c.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
    }
    @Test public void quietEditorKeepsItsKindLocationAndRadiusAcrossRotation() throws Exception {
        LastTrainStore.saveHome(c,home);String outer=LastTrainStore.notificationArea(c).json().toString();
        ActivityController<ReturnAreaEditorActivity> first=Robolectric.buildActivity(ReturnAreaEditorActivity.class,
                new Intent(c,ReturnAreaEditorActivity.class).putExtra(ReturnAreaEditorActivity.EXTRA_QUIET,true)).setup();
        StationMapView map=ReflectionHelpers.getField(first.get(),"map");assertTrue(map.getContentDescription().toString().contains("通知しない円"));
        assertEquals(Ui.MUTED,(int)ReflectionHelpers.getField(map,"singleCircleColor"));
        map.measure(View.MeasureSpec.makeMeasureSpec(824,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));map.layout(0,0,824,900);
        Bitmap image=Bitmap.createBitmap(824,900,Bitmap.Config.ARGB_8888);map.draw(new Canvas(image));image.recycle();
        long t=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,412,450,0),move=MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,612,450,0),up=MotionEvent.obtain(t,t+200,MotionEvent.ACTION_UP,612,450,0);
        map.onTouchEvent(down);map.onTouchEvent(move);map.onTouchEvent(up);down.recycle();move.recycle();up.recycle();
        double changedLon=ReflectionHelpers.getField(first.get(),"lon");assertNotEquals(home.longitude,changedLon,0.00001);
        map.setFocusedRadius(2000);Bundle state=new Bundle();first.saveInstanceState(state).pause().stop().destroy();
        try(ActivityController<ReturnAreaEditorActivity> second=Robolectric.buildActivity(ReturnAreaEditorActivity.class).create(state).start().resume().visible()){
            ((Button)ReflectionHelpers.getField(second.get(),"save")).performClick();assertTrue(second.get().isFinishing());
            assertEquals(changedLon,LastTrainStore.quietArea(c).longitude,0.000001);assertEquals(2000,LastTrainStore.quietArea(c).radius,0);
            assertEquals(outer,LastTrainStore.notificationArea(c).json().toString());assertEquals(home.json().toString(),LastTrainStore.home(c).json().toString());
        }
    }
    @Test public void closingQuietEditorWithoutSavingKeepsBothSavedCircles(){
        LastTrainStore.saveHome(c,home);
        try(ActivityController<ReturnAreaEditorActivity> a=Robolectric.buildActivity(ReturnAreaEditorActivity.class,
                new Intent(c,ReturnAreaEditorActivity.class).putExtra(ReturnAreaEditorActivity.EXTRA_QUIET,true)).setup()){
            ((StationMapView)ReflectionHelpers.getField(a.get(),"map")).setFocusedRadius(2000);a.get().onBackPressed();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(a.get().isFinishing());assertEquals(500,LastTrainStore.quietArea(c).radius,0);
            assertEquals(ReturnArea.DEFAULT_RADIUS,LastTrainStore.notificationArea(c).radius,0);
        }
    }
}
