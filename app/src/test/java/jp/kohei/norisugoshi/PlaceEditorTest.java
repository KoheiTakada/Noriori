package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, qualifiers="w412dp-h892dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaceEditorTest {
    private Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1",0).edit().clear().commit();
        context.getSharedPreferences("monitor_state",0).edit().clear().commit();
        PlaceFixtures.install(context);
    }
    private Intent intent() {
        return new Intent(context,StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID,StationRepository.ASAKADAI.id)
                .putExtra(StationEditorActivity.EXTRA_EDIT_ARM,true);
    }
    @Test public void longPressAddsASeparateThirdPlaceAndPreservesTheDestination() throws Exception {
        try (ActivityController<StationEditorActivity> controller=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup()) {
            StationEditorActivity activity=controller.get();
            ReflectionHelpers.setField(activity,"selectedArea",2);
            ReflectionHelpers.callInstanceMethod(activity,"updateSelectedPlace");
            StationMapView map=ReflectionHelpers.getField(activity,"map");
            double lat=StationRepository.ASAKADAI.latitude;
            double lon=StationRepository.ASAKADAI.longitude+Math.toDegrees(4_000.0/(6_371_000*Math.cos(Math.toRadians(lat))));
            map.setStation(lat,lon,"場所",true);
            int w=Ui.dp(context,412),h=Ui.dp(context,650);
            map.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
            map.layout(0,0,w,h);
            long now=SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,w/2f,h/2f,0);
            map.onTouchEvent(down);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(700,TimeUnit.MILLISECONDS);
            down.recycle();
            map.setFocusedRadius(700);
            save(activity);
            Station result=StationRepository.findById(context,StationRepository.ASAKADAI.id);
            assertEquals(3,result.activationAreas.size());
            assertEquals(lon,result.activationAreas.get(2).longitude,0.00001);
            assertEquals(700,result.activationAreas.get(2).radiusMeters,0);
            assertEquals(500,result.alertRadiusMeters,0);
            assertEquals(StationRepository.ASAKADAI.longitude,result.longitude,0);
            assertFalse(MonitorStateStore.isArmed(context,result));
        }
    }
    @Test public void removingOnePlaceAndRotatingKeepsTheRemainingPlaces() throws Exception {
        ActivityController<StationEditorActivity> first=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup();
        android.widget.LinearLayout chips=ReflectionHelpers.getField(first.get(),"areaChips");
        chips.getChildAt(0).performClick();
        Bundle state=new Bundle();
        first.saveInstanceState(state).pause().stop().destroy();
        try(ActivityController<StationEditorActivity> second=Robolectric.buildActivity(StationEditorActivity.class,intent())
                .create(state).start().resume().visible()) {
            save(second.get());
            Station result=StationRepository.findById(context,StationRepository.ASAKADAI.id);
            assertEquals(1,result.activationAreas.size());
            assertEquals("南側の駅",result.activationAreas.get(0).name);
        }
    }
    @Test public void chipTapSelectsThenDeletesAndAdditionIsLast(){
        try(ActivityController<StationEditorActivity> controller=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup()){
            StationEditorActivity a=controller.get();
            android.widget.LinearLayout chips=ReflectionHelpers.getField(a,"areaChips");
            java.util.List<ActivationArea> areas=ReflectionHelpers.getField(a,"areas");
            Button south=(Button)chips.getChildAt(1);assertFalse(south.isSelected());
            assertNull(south.getCompoundDrawablesRelative()[0]);assertNull(south.getCompoundDrawablesRelative()[2]);
            south.performClick();assertEquals(2,areas.size());
            south=(Button)chips.getChildAt(1);assertTrue(south.isSelected());
            assertNull(south.getCompoundDrawablesRelative()[0]);assertNotNull(south.getCompoundDrawablesRelative()[2]);
            assertEquals("オンにする場所を追加",chips.getChildAt(chips.getChildCount()-1).getContentDescription());
            south.performClick();assertEquals(1,areas.size());assertEquals("北側の駅",areas.get(0).name);
            assertFalse(chips.getChildAt(0).isSelected());
        }
    }
    @Test public void overlapIsRejectedWithoutChangingTheStoredPlaces() {
        try(ActivityController<StationEditorActivity> controller=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup()) {
            ReflectionHelpers.callInstanceMethod(controller.get(),"setArmPlace",
                    ReflectionHelpers.ClassParameter.from(String.class,"近すぎる場所"),
                    ReflectionHelpers.ClassParameter.from(double.class,StationRepository.ASAKADAI.latitude),
                    ReflectionHelpers.ClassParameter.from(double.class,StationRepository.ASAKADAI.longitude));
            ((Button)ReflectionHelpers.getField(controller.get(),"saveButton")).performClick();
            assertFalse(controller.get().isFinishing());
            assertEquals("北側の駅",StationRepository.findById(context,StationRepository.ASAKADAI.id).activationAreas.get(0).name);
        }
    }
    @Test public void tappingEachCircleSelectsItsEditorWithoutReplacingPlaces() {
        try(ActivityController<StationEditorActivity> c=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup()) {
            StationEditorActivity a=c.get();StationMapView map=ReflectionHelpers.getField(a,"map");
            java.util.List<ActivationArea> areas=ReflectionHelpers.getField(a,"areas");
            ActivationArea south=areas.get(1);
            tapMapCenterAt(map,south.latitude,south.longitude);
            assertEquals(1,(int)ReflectionHelpers.getField(a,"selectedArea"));
            assertTrue((boolean)ReflectionHelpers.getField(a,"editingArm"));
            map.setFocusedRadius(300);
            tapMapCenterAt(map,StationRepository.ASAKADAI.latitude,StationRepository.ASAKADAI.longitude);
            assertFalse((boolean)ReflectionHelpers.getField(a,"editingArm"));
            assertEquals(500,map.getFocusedRadius(),0);
            assertEquals(2,areas.size());assertEquals(300,areas.get(1).radiusMeters,0);
            assertEquals("北側の駅",areas.get(0).name);
        }
    }
    private void tapMapCenterAt(StationMapView map,double latitude,double longitude) {
        int w=Ui.dp(context,412),h=Ui.dp(context,500);
        map.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));map.layout(0,0,w,h);
        ReflectionHelpers.setField(map,"cameraLatitude",latitude);ReflectionHelpers.setField(map,"cameraLongitude",longitude);ReflectionHelpers.setField(map,"cameraReady",true);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);map.draw(new android.graphics.Canvas(bitmap));bitmap.recycle();
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,w/2f,h/2f,0);
        MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,w/2f,h/2f,0);map.onTouchEvent(down);map.onTouchEvent(up);down.recycle();up.recycle();
    }
    @Test public void initialStateIsOffAndSavedOnStateSurvivesReload() {
        Station station=StationRepository.findById(context,StationRepository.ASAKADAI.id);
        MonitorStateStore.migrateForReliableArming(context);
        assertFalse(MonitorStateStore.isArmed(context,station));
        MonitorStateStore.setArmed(context,station,true);
        MonitorStateStore.migrateForReliableArming(context);
        assertTrue(MonitorStateStore.isArmed(context,StationRepository.findById(context,station.id)));
    }
    @Test public void missingActivationPlacesCannotReplaceTheSavedStation() {
        try(ActivityController<StationEditorActivity> controller=Robolectric.buildActivity(StationEditorActivity.class,intent()).setup()) {
            ReflectionHelpers.callInstanceMethod(controller.get(),"selectMode",
                    ReflectionHelpers.ClassParameter.from(boolean.class,false));
            java.util.List<ActivationArea> areas=ReflectionHelpers.getField(controller.get(),"areas");
            areas.clear();
            ((Button)ReflectionHelpers.getField(controller.get(),"saveButton")).performClick();
            assertFalse(controller.get().isFinishing());
            assertTrue((boolean)ReflectionHelpers.getField(controller.get(),"editingArm"));
            assertEquals("場所を追加",((Button)ReflectionHelpers.getField(controller.get(),"saveButton")).getText().toString());
            assertEquals(2,StationRepository.findById(context,StationRepository.ASAKADAI.id).activationAreas.size());
            assertTrue(StationRepository.findById(context,StationRepository.ASAKADAI.id).isConfigured());
        }
    }
    private void save(StationEditorActivity activity) throws Exception {
        ((Button)ReflectionHelpers.getField(activity,"saveButton")).performClick();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!activity.isFinishing() && System.nanoTime()<end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);
        }
        assertTrue("Save must complete",activity.isFinishing());
    }
}
