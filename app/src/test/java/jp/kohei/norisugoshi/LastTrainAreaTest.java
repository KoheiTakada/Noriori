package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29,qualifiers="w412dp-h892dp-xhdpi")
public class LastTrainAreaTest {
    Context c;
    HomeStation home=new HomeStation("池袋駅","山手線",35.730,139.711);
    @After public void restoreClock(){LastTrainFixtures.reset();}
    @Before public void reset(){LastTrainFixtures.night();c=RuntimeEnvironment.getApplication();LastTrainStore.prefs(c).edit().clear().commit();}
    @Test public void legacyHomeMigratesOnceToAStationWithoutInvertingOldTravelCircles() throws Exception {
        String travels=new JSONArray().put(new ReturnArea("old","京都",35.01,135.76,20_000).json()).toString();
        LastTrainStore.prefs(c).edit().putString("home",new ReturnArea("home","家",35.730,139.711,1800).json().toString())
                .putString("travels",travels).putInt("minutes",30).putString("zone","outside").commit();
        HomeStation migrated=LastTrainStore.home(c);assertEquals("池袋駅",migrated.name);
        assertFalse(migrated.json().has("radius"));assertFalse(LastTrainStore.prefs(c).contains("home"));
        assertEquals(travels,LastTrainStore.prefs(c).getString("travels",""));
        assertEquals(1800,LastTrainStore.quietArea(c).radius,0);
        assertEquals(30,LastTrainStore.minutes(c));assertEquals(150_000,LastTrainStore.notificationArea(c).radius,0);
        assertFalse(LastTrainStore.prefs(c).contains("zone"));
        long generation=LastTrainStore.generation(c);LastTrainStore.home(c);assertEquals(generation,LastTrainStore.generation(c));
    }
    @Test public void insideNotifiesAndOutsideSuppressesWithUncertainBoundariesPreserved(){
        ReturnArea region=LastTrainStore.defaultArea(home);
        assertEquals("notify",LastTrainEngine.zone(35.5,139.7,20,LastTrainStore.defaultQuietArea(home),region,"unknown"));
        assertEquals("travel",LastTrainEngine.zone(35,135,20,LastTrainStore.defaultQuietArea(home),region,"unknown"));
        assertEquals("home",LastTrainEngine.zone(home.latitude,home.longitude,20,LastTrainStore.defaultQuietArea(home),region,"unknown"));
        double[] edge=Geo.destination(region.latitude,region.longitude,region.radius,90);
        assertEquals("notify",LastTrainEngine.zone(edge[0],edge[1],100,LastTrainStore.defaultQuietArea(home),region,"notify"));
        assertEquals("travel",LastTrainEngine.zone(edge[0],edge[1],100,LastTrainStore.defaultQuietArea(home),region,"travel"));
    }
    @Test public void leavingRegionCancelsExistingReservationAndReentryAllowsSearch(){
        LastTrainStore.saveHome(c,home);long now=LastTrainEngine.now();
        LastTrainRoute route=new LastTrainRoute(LastTrainEngine.day(now),"池袋","山手線",now+60*60_000L,now+120*60_000L,now,35.5,139.7,false);
        LastTrainStore.route(c,route);LastTrainStore.prefs(c).edit().putString("zone","notify").commit();LastTrainCoordinator.restore(c);
        assertFalse(Shadows.shadowOf(c.getSystemService(android.app.AlarmManager.class)).getScheduledAlarms().isEmpty());
        android.location.Location fix=new android.location.Location("test");fix.setLatitude(35);fix.setLongitude(135);fix.setAccuracy(20);fix.setTime(now);
        LastTrainCoordinator.observe(c,fix);
        assertEquals("travel",LastTrainStore.prefs(c).getString("zone",""));
        assertTrue(Shadows.shadowOf(c.getSystemService(android.app.AlarmManager.class)).getScheduledAlarms().isEmpty());
        assertFalse(LastTrainStore.alerting(c));
        assertEquals("notify",LastTrainEngine.zone(35.5,139.7,20,LastTrainStore.defaultQuietArea(home),LastTrainStore.notificationArea(c),"travel"));
    }
    @Test public void radiusCanCoverARegionAndExpandBeyondThePreviousLimit()throws Exception{
        LastTrainStore.saveHome(c,home);ReturnArea area=LastTrainStore.notificationArea(c);
        // Mainland extremities around the Tokyo, Kanagawa, Saitama and Chiba rail region.
        for(double[] point:new double[][]{{35.72,140.87},{34.91,139.82},{35.20,139.02},{36.10,138.86},{35.77,138.99}})
            assertEquals("notify",LastTrainEngine.zone(point[0],point[1],20,LastTrainStore.defaultQuietArea(home),area,"unknown"));
        ReturnArea wide=new ReturnArea("notification-area","通知するエリア",35.8,139.7,1_000_000);
        assertTrue(LastTrainStore.saveArea(c,wide));assertEquals(wide.radius,LastTrainStore.notificationArea(c).radius,0);
        assertEquals(100,ReturnArea.radiusAt(0),0);assertEquals(1_000_000,ReturnArea.radiusAt(1000),.0001);
    }
    @Test public void mapDragChangesRegionAndRotationSaveKeepsItsWideRadius(){
        LastTrainStore.saveHome(c,home);
        ActivityController<ReturnAreaEditorActivity> first=Robolectric.buildActivity(ReturnAreaEditorActivity.class).setup();
        StationMapView map=ReflectionHelpers.getField(first.get(),"map");
        int width=824,height=900;map.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));map.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);map.draw(new Canvas(bitmap));bitmap.recycle();
        long t=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,412,450,0),move=MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,612,450,0),up=MotionEvent.obtain(t,t+200,MotionEvent.ACTION_UP,612,450,0);
        map.onTouchEvent(down);map.onTouchEvent(move);map.onTouchEvent(up);down.recycle();move.recycle();up.recycle();
        double changedLon=ReflectionHelpers.getField(first.get(),"lon");assertNotEquals(home.longitude,changedLon,.01);
        map.setFocusedRadius(500_000);assertTrue((double)ReflectionHelpers.getField(map,"cameraZoom")<10);
        Bundle state=new Bundle();first.saveInstanceState(state).pause().stop().destroy();
        try(ActivityController<ReturnAreaEditorActivity> second=Robolectric.buildActivity(ReturnAreaEditorActivity.class).create(state).start().resume().visible()){
            ((Button)ReflectionHelpers.getField(second.get(),"save")).performClick();
            assertEquals(changedLon,LastTrainStore.notificationArea(c).longitude,.000001);
            assertEquals(500_000,LastTrainStore.notificationArea(c).radius,0);
        }
    }
    @Test public void stationChoiceSavesImmediatelyAndHasNoRadiusControl() throws Exception {
        try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class,new Intent(c,MainActivity.class).putExtra("last_train",true)).setup()){
            View root=activity.get().findViewById(android.R.id.content);find(root,"家の最寄り駅を選ぶ").performClick();
            StationPickerDialog dialog=(StationPickerDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNull(findType(dialog.getWindow().getDecorView(),SeekBar.class));assertNull(findType(dialog.getWindow().getDecorView(),StationMapView.class));
            EditText query=ReflectionHelpers.getField(dialog,"query");query.setText("池袋");ListView list=(ListView)findType(dialog.getWindow().getDecorView(),ListView.class);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(list.getAdapter().getCount()==0&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS);Thread.sleep(10);}
            assertTrue(list.getAdapter().getCount()>0);list.performItemClick(list.getAdapter().getView(0,null,list),0,0);
            assertEquals("池袋駅",LastTrainStore.home(c).name);assertFalse(dialog.isShowing());
        }
    }
    private View find(View v,String name){if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),name);if(child!=null)return child;}return null;}
    private View findType(View v,Class<?> type){if(type.isInstance(v))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=findType(((ViewGroup)v).getChildAt(i),type);if(child!=null)return child;}return null;}
}
