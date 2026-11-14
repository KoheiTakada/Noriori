package jp.kohei.norisugoshi;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.android.gms.location.*;
import com.google.android.gms.tasks.CancellationTokenSource;

/** Edits one of the two independently saved last-train regions. */
public final class ReturnAreaEditorActivity extends Activity {
    static final String EXTRA_QUIET="quiet_area";
    private boolean saving, updating, quiet;
    private double lat, lon, radius;
    private double initialLat, initialLon, initialRadius;
    private StationMapView map;
    private Button save;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);DesignSystem.window(this);
        HomeStation home=LastTrainStore.home(this);
        quiet=state==null?getIntent().getBooleanExtra(EXTRA_QUIET,false):state.getBoolean(EXTRA_QUIET,false);
        if(quiet&&home==null){setResult(RESULT_CANCELED);finish();return;}
        ReturnArea area=quiet?LastTrainStore.quietArea(this):LastTrainStore.notificationArea(this);
        if(area==null)area=LastTrainStore.defaultArea(home);
        lat=initialLat=area.latitude;lon=initialLon=area.longitude;radius=initialRadius=area.radius;
        if(state!=null){lat=state.getDouble("lat",lat);lon=state.getDouble("lon",lon);radius=state.getDouble("radius",radius);}
        setContentView(screen());
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,this::close);
    }
    private View screen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(DesignTokens.Color.CANVAS);Ui.applyInsets(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0);
        bar.addView(Ui.iconButton(this,R.drawable.ic_close,"閉じる",this::close),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,56)));
        bar.addView(Ui.text(this,quiet?"通知しないエリア":"通知するエリア",22,true),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        LinearLayout body=new LinearLayout(this);boolean wide=DesignSystem.wide(this);body.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout mapFrame=new FrameLayout(this);map=new StationMapView(this);map.setEditable(true);
        map.setAreaSelectionMode(true);map.setFocusedRing(RadiusSettings.Ring.ALERT);map.setSingleCircleRadius(radius);
        if(quiet){map.setSingleCircleColor(Ui.MUTED);map.setSingleCircleLabel("通知しない円");}
        map.setStation(lat,lon,"",true);mapFrame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        ImageButton current=Ui.iconButton(this,R.drawable.ic_center,"現在地へ移動",this::current);
        current.setBackground(Ui.background(this,DesignTokens.Color.SURFACE,DesignTokens.Shape.PILL));
        FrameLayout.LayoutParams currentParams=new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.END|Gravity.BOTTOM);
        currentParams.setMargins(0,0,Ui.dp(this,16),Ui.dp(this,24));mapFrame.addView(current,currentParams);
        body.addView(mapFrame,wide?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setGravity(Gravity.BOTTOM);
        sheet.setPadding(Ui.dp(this,24),Ui.dp(this,20),Ui.dp(this,24),Ui.dp(this,24));
        sheet.setBackground(Ui.background(this,DesignTokens.Color.SURFACE,DesignTokens.Shape.LARGE));
        if(!quiet){TextView hint=Ui.text(this,"この円の中だけ通知",16,true);sheet.addView(hint);}
        TextView move=Ui.text(this,"地図を動かして選ぶ",14,false);move.setTextColor(Ui.MUTED);sheet.addView(move,Ui.margins(this,8,0));
        SeekBar slider=new SeekBar(this);slider.setMax(1000);DesignSystem.slider(slider);slider.setContentDescription(quiet?"通知しない範囲の広さ":"通知する範囲の広さ");
        slider.setProgress(ReturnArea.progressAt(radius));sheet.addView(slider,Ui.margins(this,8,0));
        LinearLayout ends=new LinearLayout(this);TextView small=Ui.text(this,"狭く",12,false),large=Ui.text(this,"広く",12,false);large.setGravity(Gravity.END);
        small.setTextColor(Ui.MUTED);large.setTextColor(Ui.MUTED);ends.addView(small,new LinearLayout.LayoutParams(0,-2,1));ends.addView(large,new LinearLayout.LayoutParams(0,-2,1));sheet.addView(ends);
        save=Ui.button(this,"保存",true,this::save);sheet.addView(save,Ui.margins(this,16,0));
        ScrollView sc=new ScrollView(this);sc.setFillViewport(wide);sc.addView(sheet);body.addView(sc,wide?new LinearLayout.LayoutParams(Ui.dp(this,320),-1):new LinearLayout.LayoutParams(-1,-2));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean user){if(user)map.setFocusedRadius(ReturnArea.radiusAt(value));}
            @Override public void onStartTrackingTouch(SeekBar b){map.beginRadiusGesture();}
            @Override public void onStopTrackingTouch(SeekBar b){map.endRadiusGesture();}
        });
        map.setOnRadiiChangedListener(values->{radius=values.alertMeters;map.fitCircle();
            if(!updating){updating=true;slider.setProgress(ReturnArea.progressAt(radius));updating=false;}});
        map.setOnLocationPickedListener((latitude,longitude)->{lat=latitude;lon=longitude;});
        return root;
    }
    @android.annotation.SuppressLint("MissingPermission") private void current(){
        if(saving)return;
        if(!PermissionUtils.hasFineLocation(this)){Toast.makeText(this,"位置情報を許可",Toast.LENGTH_SHORT).show();return;}
        LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(
                new CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setDurationMillis(15_000).build(),
                new CancellationTokenSource().getToken()).addOnSuccessListener(fix->{
            if(isFinishing()||isDestroyed()||saving)return;
            if(fix!=null&&Double.isFinite(fix.getLatitude())&&Math.abs(fix.getLatitude())<=85
                    &&Double.isFinite(fix.getLongitude())&&Math.abs(fix.getLongitude())<=180){
                lat=fix.getLatitude();lon=fix.getLongitude();map.setStation(lat,lon,"",true);
            }else Toast.makeText(this,"現在地を確認できない",Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,"現在地を確認できない",Toast.LENGTH_SHORT).show();});
    }
    private void save(){
        if(saving)return;
        ReturnArea area=new ReturnArea(quiet?LastTrainStore.QUIET_AREA_ID:"notification-area",quiet?"通知しないエリア":"通知するエリア",lat,lon,radius);saving=true;save.setEnabled(false);
        if(!(quiet?LastTrainStore.saveQuietArea(this,area):LastTrainStore.saveArea(this,area))){saving=false;save.setEnabled(true);Toast.makeText(this,"保存できない",Toast.LENGTH_SHORT).show();return;}
        LastTrainCoordinator.stopActive(this,false);LastTrainCoordinator.stopWatch(this);LastTrainAlarms.cancel(this);
        LastTrainSearchJob.cancel(this);
        LastTrainCoordinator.configure(this);setResult(RESULT_OK);finish();
    }
    private void close(){
        if(saving)return;
        if(lat==initialLat&&lon==initialLon&&radius==initialRadius){finish();return;}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("変更を保存せず閉じる？").setNegativeButton("編集を続ける",null)
                .setPositiveButton("閉じる",(x,y)->finish()).show();DesignSystem.styleDialog(d,false);
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);
        out.putBoolean(EXTRA_QUIET,quiet);
        out.putDouble("lat",lat);out.putDouble("lon",lon);out.putDouble("radius",radius);}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){close();}
}
