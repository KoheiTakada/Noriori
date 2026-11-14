package jp.kohei.norisugoshi;
import static org.junit.Assert.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.Button;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.io.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29,qualifiers="w412dp-h892dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LastTrainRenderTest {
    @After public void restoreClock(){LastTrainFixtures.reset();}
    @Before public void reset()throws Exception{LastTrainFixtures.night();
        LastTrainStore.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
        android.util.LruCache<String,Bitmap> tiles=org.robolectric.util.ReflectionHelpers.getStaticField(StationMapView.class,"TILE_CACHE");
        for(int z:new int[]{6,7})for(int x:z==6?new int[]{56,57}:new int[]{112,113,114})for(int y:z==6?new int[]{24,25}:new int[]{49,50,51}){
            try(InputStream stream=getClass().getResourceAsStream("/maps/osm-"+z+"-"+x+"-"+y+".png")){
                assertNotNull(stream);Bitmap tile=BitmapFactory.decodeStream(stream);assertNotNull(tile);tiles.put(z+"/"+x+"/"+y,tile);
            }
        }
    }
    private void seed(){
        Context c=RuntimeEnvironment.getApplication();long now=LastTrainEngine.now();
        LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",StationRepository.ASAKADAI.latitude,StationRepository.ASAKADAI.longitude));
        LastTrainStore.prefs(c).edit().putString("zone","notify").commit();
        LastTrainStore.route(c,new LastTrainRoute(LastTrainEngine.day(now),"池袋","東武東上線",now+60*60_000L,now+120*60_000L,now,35.73,139.71,false));
    }
    @Test public void phone()throws Exception{seed();renderHome("last-train-phone",false);}
    @Test @Config(qualifiers="w840dp-h740dp-xhdpi") public void unfolded()throws Exception{seed();renderHome("last-train-unfolded",true);}
    @Test @Config(qualifiers="w840dp-h740dp-xhdpi") public void unfoldedRightEdgeSurvivesRtl()throws Exception{
        renderRightEdge("last-train-unfolded-rtl");
    }
    @Test @Config(sdk=37,qualifiers="w840dp-h812dp-xhdpi") public void unfoldedAndroid17RightEdge()throws Exception{
        renderRightEdge("last-train-unfolded-android17");
    }
    private void renderRightEdge(String name)throws Exception{
        seed();
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra("last_train",true);
        try(ActivityController<MainActivity> a=Robolectric.buildActivity(MainActivity.class,intent).setup()){
            View root=a.get().findViewById(android.R.id.content);
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            render(root,name);
            View ride=find(root,"降車タブ"),last=find(root,"終電タブ");
            int[] at=new int[2];ride.getLocationOnScreen(at);
            assertTrue("Navigation must stay on the physical right",at[0]>root.getWidth()/2);
            assertTrue("Navigation must remain in thumb reach",at[1]>root.getHeight()/2);
            ride.performClick();assertTrue(ride.isSelected());assertFalse(last.isSelected());
            last.performClick();assertTrue(last.isSelected());
        }
    }
    @Test public void fetchError()throws Exception{
        LastTrainStore.saveHome(RuntimeEnvironment.getApplication(),new HomeStation("朝霞台駅","東武東上線",35.8,139.5));
        LastTrainStore.problem(RuntimeEnvironment.getApplication(),"終電を確認できない");
        renderHome("last-train-error",false);
    }
    @Test public void empty()throws Exception{renderHome("last-train-empty",false);}
    private void renderHome(String name,boolean wide)throws Exception{
        Intent i=new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra("last_train",true);
        try(ActivityController<MainActivity> a=Robolectric.buildActivity(MainActivity.class,i).setup()){
            View root=a.get().findViewById(android.R.id.content);render(root,name);
            View last=find(root,"終電タブ"),ride=find(root,"降車タブ");
            assertNotNull(last);assertNotNull(ride);assertTrue(last.isSelected());
            int[] one=new int[2],two=new int[2];ride.getLocationOnScreen(one);last.getLocationOnScreen(two);
            if(wide){assertEquals(one[0],two[0]);assertTrue(two[1]>one[1]);
                assertTrue("Navigation must stay on the physical right",one[0]>root.getWidth()/2);
                assertTrue("Navigation must remain in thumb reach",one[1]>root.getHeight()/2);}
            else{assertEquals(one[1],two[1]);assertTrue(two[0]>one[0]);}
            assertTrue(last.getHeight()>=Ui.dp(a.get(),48));
            View add=find(root,"終電を通知するエリアを編集");int[] addPosition=new int[2];add.getLocationOnScreen(addPosition);
            assertTrue("Frequent actions belong to lower half",addPosition[1]>root.getHeight()/2);
            if(LastTrainStore.home(a.get())!=null)assertNotNull(find(root,"終電を通知しないエリアを編集"));
        }
    }
    @Test public void areaEditor()throws Exception{
        Context c=RuntimeEnvironment.getApplication();
        LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",StationRepository.ASAKADAI.latitude,StationRepository.ASAKADAI.longitude));
        try(ActivityController<ReturnAreaEditorActivity>a=Robolectric.buildActivity(ReturnAreaEditorActivity.class,new Intent(c,ReturnAreaEditorActivity.class)).setup()){
            render(a.get().findViewById(android.R.id.content),"notification-area-editor");
        }
    }
    @Test @Config(qualifiers="w840dp-h740dp-xhdpi") public void wideAreaEditor()throws Exception{
        Context c=RuntimeEnvironment.getApplication();LastTrainStore.saveHome(c,new HomeStation("池袋駅","山手線",35.73,139.71));
        try(ActivityController<ReturnAreaEditorActivity>a=Robolectric.buildActivity(ReturnAreaEditorActivity.class).setup()){
            View root=a.get().findViewById(android.R.id.content);render(root,"notification-area-unfolded");
            android.widget.Button save=org.robolectric.util.ReflectionHelpers.getField(a.get(),"save");
            int[] position=new int[2];save.getLocationOnScreen(position);assertTrue(position[1]>root.getHeight()/2);
        }
    }
    @Test public void homeStationPicker()throws Exception{
        seed();Context c=RuntimeEnvironment.getApplication();
        try(ActivityController<MainActivity>a=Robolectric.buildActivity(MainActivity.class,new Intent(c,MainActivity.class).putExtra("last_train",true)).setup()){
            View background=a.get().findViewById(android.R.id.content);render(background,"last-train-phone");
            find(background,"家の最寄り駅を選ぶ").performClick();
            StationPickerDialog picker=(StationPickerDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            View modal=picker.getWindow().getDecorView();android.widget.ListView list=(android.widget.ListView)find(modal,"駅の候補");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(list.getAdapter().getCount()==0&&System.nanoTime()<deadline){
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(100,java.util.concurrent.TimeUnit.MILLISECONDS);Thread.sleep(10);
            }
            assertTrue(list.getAdapter().getCount()>0);
            int width=Ui.dp(c,412),height=Ui.dp(c,892),sheetHeight=Ui.dp(c,DesignTokens.Size.SHEET_MAX);
            modal.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(sheetHeight,View.MeasureSpec.EXACTLY));modal.layout(0,0,width,sheetHeight);
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);background.draw(canvas);
            Paint dim=new Paint();dim.setColor(Color.argb(90,0,0,0));canvas.drawRect(0,0,width,height,dim);canvas.translate(0,height-sheetHeight);modal.draw(canvas);
            try(FileOutputStream output=new FileOutputStream("build/qa/home-station-picker.png")){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();picker.dismiss();
        }
    }
    @Test public void quietAreaEditor()throws Exception{renderQuiet("quiet-area-editor");}
    @Test @Config(qualifiers="w840dp-h740dp-xhdpi") public void wideQuietAreaEditor()throws Exception{renderQuiet("quiet-area-unfolded");}
    private void renderQuiet(String name)throws Exception{
        Context c=RuntimeEnvironment.getApplication();LastTrainStore.saveHome(c,new HomeStation("朝霞台駅","東武東上線",StationRepository.ASAKADAI.latitude,StationRepository.ASAKADAI.longitude));
        Intent i=new Intent(c,ReturnAreaEditorActivity.class).putExtra(ReturnAreaEditorActivity.EXTRA_QUIET,true);
        try(ActivityController<ReturnAreaEditorActivity> a=Robolectric.buildActivity(ReturnAreaEditorActivity.class,i).setup()){
            View root=a.get().findViewById(android.R.id.content);render(root,name);
            Button save=org.robolectric.util.ReflectionHelpers.getField(a.get(),"save");
            int[] position=new int[2];save.getLocationOnScreen(position);assertTrue(position[1]>root.getHeight()/2);
        }
    }
    private void render(View root,String name)throws Exception{
        int width=Ui.dp(root.getContext(),root.getResources().getConfiguration().screenWidthDp),height=Ui.dp(root.getContext(),root.getResources().getConfiguration().screenHeightDp);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
        File d=new File("build/qa");d.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(d,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,f);}image.recycle();
    }
    private View find(View v,String name){
        if(name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int j=0;j<((ViewGroup)v).getChildCount();j++){View child=find(((ViewGroup)v).getChildAt(j),name);if(child!=null)return child;}
        return null;
    }
}
