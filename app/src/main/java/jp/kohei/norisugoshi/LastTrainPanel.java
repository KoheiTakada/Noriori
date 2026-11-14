package jp.kohei.norisugoshi;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

final class LastTrainPanel extends ScrollView {
    private final Activity activity;
    private String rendered="";
    private LinearLayout column;
    LastTrainPanel(Activity a){super(a);activity=a;setFillViewport(true);setVerticalScrollBarEnabled(false);
        setBackgroundColor(DesignTokens.Color.CANVAS);
        FrameLayout frame=new FrameLayout(a);addView(frame);
        column=new DesignSystem.ContentColumn(a);column.setPadding(Ui.dp(a,20),Ui.dp(a,32),Ui.dp(a,20),Ui.dp(a,32));
        frame.addView(column,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));refresh();}
    void refresh(){
        boolean active=LastTrainEngine.activeTime(LastTrainEngine.now());
        String snapshot=LastTrainStore.prefs(activity).getAll().toString()+LastTrainAlarms.allowed(activity)
                +PermissionUtils.hasFineLocation(activity)+PermissionUtils.hasBackgroundLocation(activity)
                +PermissionUtils.hasNotificationPermission(activity)+active;
        if(snapshot.equals(rendered))return;rendered=snapshot;column.removeAllViews();
        HomeStation home=LastTrainStore.home(activity);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=DesignSystem.text(activity,"終電",DesignSystem.Text.TITLE);
        title.setContentDescription("終電。長押しで履歴");title.setOnLongClickListener(v->{((MainActivity)activity).openLogs();return true;});
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        column.addView(header);
        if(home!=null){
            LinearLayout card=card();LastTrainRoute r=LastTrainStore.route(activity);
            String zone=LastTrainStore.prefs(activity).getString("zone","unknown");boolean muted=LastTrainStore.muted(activity,LastTrainEngine.now());
            if(!active){card.setVisibility(View.GONE);
            }else if(LastTrainStore.alerting(activity)){
                card.addView(Ui.text(activity,r==null?"終電":LastTrainEngine.time(r.leaveAt)+"までに出発",22,true));
                if(r!=null)card.addView(Ui.text(activity,r.station+" · "+r.path,14,false),Ui.margins(activity,8,0));
            }else if(muted){card.addView(Ui.text(activity,"今夜はオフ",22,true));
            }else if("home".equals(zone)||"travel".equals(zone)){
                card.setVisibility(View.GONE);
            }else if(r!=null&&r.day.equals(LastTrainEngine.day(LastTrainEngine.now()))){
                if(r.walkOnly)card.addView(Ui.text(activity,"徒歩で帰れる",22,true));
                else{
                    card.addView(Ui.text(activity,LastTrainEngine.time(r.leaveAt),40,true));card.addView(Ui.text(activity,"までに出発",14,false),Ui.margins(activity,4,0));
                    card.addView(Ui.text(activity,r.station,16,true),Ui.margins(activity,16,0));
                    card.addView(Ui.text(activity,r.path,14,false),Ui.margins(activity,8,0));
                    if(!r.credits.isEmpty()){
                        TextView credit=Ui.text(activity,"Transit · データ出典",12,false);credit.setTextColor(Ui.MUTED);
                        credit.setOnClickListener(v->{AlertDialog d=new AlertDialog.Builder(activity).setMessage(r.credits)
                                .setPositiveButton("閉じる",null).show();DesignSystem.styleDialog(d,false);});
                        credit.setMinimumHeight(Ui.dp(activity,48));card.addView(credit,Ui.margins(activity,4,0));
                    }
                    if(LastTrainEngine.now()-r.fetchedAt>120_000)card.addView(Ui.text(activity,LastTrainEngine.time(r.fetchedAt)+"の位置から",12,false),Ui.margins(activity,8,0));
                }
            }else{TextView message=Ui.text(activity,LastTrainStore.problem(activity).isEmpty()?"終電を確認中":LastTrainStore.problem(activity),16,false);message.setTextColor(Ui.MUTED);card.addView(message);}
            column.addView(card,Ui.margins(activity,24,16));
        }
        column.addView(new View(activity),new LinearLayout.LayoutParams(-1,0,1f));
        column.addView(homeRow(home),Ui.margins(activity,24,8));
        if(home!=null)column.addView(areaRow(LastTrainStore.quietArea(activity),true),Ui.margins(activity,8,8));
        ReturnArea area=LastTrainStore.notificationArea(activity);
        if(area==null)area=LastTrainStore.defaultArea(home);
        column.addView(areaRow(area,false),Ui.margins(activity,8,8));
        if(home!=null){
            if(!PermissionUtils.hasFineLocation(activity)||!PermissionUtils.hasBackgroundLocation(activity))
                column.addView(Ui.button(activity,"位置情報を常に許可",true,()->((MainActivity)activity).requestPermissionSetup(PermissionSetup.BACKGROUND)),Ui.margins(activity,0,8));
            if(!PermissionUtils.hasNotificationPermission(activity))
                column.addView(Ui.button(activity,"通知を許可",true,()->((MainActivity)activity).requestPermissionSetup(PermissionSetup.NOTIFICATIONS)),Ui.margins(activity,0,8));
            if(Build.VERSION.SDK_INT>=31&&!LastTrainAlarms.allowed(activity))column.addView(Ui.button(activity,"アラームを許可",true,()->((MainActivity)activity).requestPermissionSetup(PermissionSetup.ALARMS)),Ui.margins(activity,8,0));
            LinearLayout controls=new LinearLayout(activity);controls.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
            LastTrainRoute route=LastTrainStore.route(activity);
            if(LastTrainStore.alerting(activity)){
                controls.addView(DesignSystem.button(activity,"停止",DesignSystem.Action.DANGER,()->{
                    LastTrainCoordinator.stopActive(activity,true);TravelMonitorService.stopAll(activity);refresh();}),new LinearLayout.LayoutParams(0,Ui.dp(activity,52),1));
            }else if(LastTrainStore.muted(activity,LastTrainEngine.now())){
                controls.addView(Ui.button(activity,"戻す",false,()->{
                    LastTrainStore.prefs(activity).edit().remove("mutedDay").remove("announcedDay").commit();LastTrainCoordinator.configure(activity);refresh();}),new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));
            }else if(active&&route!=null&&!route.walkOnly){
                controls.addView(Ui.button(activity,"今夜はオフ",false,()->{LastTrainCoordinator.stopActive(activity,true);refresh();}),
                        new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));
            }
            Button advance=Ui.button(activity,LastTrainStore.minutes(activity)+"分前",false,this::timing);
            advance.setContentDescription("終電通知の余裕を変更");advance.setPadding(Ui.dp(activity,12),0,Ui.dp(activity,12),0);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,Ui.dp(activity,48));params.setMargins(Ui.dp(activity,8),0,0,0);
            controls.addView(advance,params);column.addView(controls,Ui.margins(activity,8,0));
        }
    }
    private LinearLayout card(){LinearLayout c=new LinearLayout(activity);c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24));
        c.setBackground(Ui.background(activity,DesignTokens.Color.SURFACE,DesignTokens.Shape.LARGE));return c;}
    private LinearLayout homeRow(HomeStation home){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
        row.setMinimumHeight(Ui.dp(activity,80));row.setBackground(DesignSystem.ripple(activity,DesignTokens.Color.SURFACE,DesignTokens.Shape.LARGE,Ui.BLUE));
        row.addView(DesignSystem.icon(activity,R.drawable.ic_home,Ui.BLUE,24));
        LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(Ui.dp(activity,16),0,Ui.dp(activity,8),0);
        labels.addView(Ui.text(activity,home==null?"家の最寄り駅":home.name,16,true));
        if(home!=null){TextView caption=Ui.text(activity,"家の最寄り駅",12,false);caption.setTextColor(Ui.MUTED);labels.addView(caption,Ui.margins(activity,4,0));}
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.addView(DesignSystem.icon(activity,home==null?R.drawable.ic_add:R.drawable.ic_chevron,Ui.MUTED,24));
        row.setContentDescription("家の最寄り駅を選ぶ");row.setOnClickListener(v->chooseHome());return row;
    }
    private LinearLayout areaRow(ReturnArea area,boolean quiet){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
        row.setMinimumHeight(Ui.dp(activity,80));row.setBackground(DesignSystem.ripple(activity,DesignTokens.Color.SURFACE,DesignTokens.Shape.LARGE,Ui.BLUE));
        row.addView(DesignSystem.icon(activity,R.drawable.ic_area,quiet?Ui.MUTED:Ui.BLUE,24));
        TextView name=Ui.text(activity,quiet?"通知しないエリア":"通知するエリア",16,true);name.setPadding(Ui.dp(activity,16),0,Ui.dp(activity,8),0);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(DesignSystem.icon(activity,R.drawable.ic_chevron,Ui.MUTED,24));
        row.setContentDescription(quiet?"終電を通知しないエリアを編集":"終電を通知するエリアを編集");row.setOnClickListener(v->edit(area,quiet));return row;
    }
    private void edit(ReturnArea a,boolean quiet){Intent i=new Intent(activity,ReturnAreaEditorActivity.class).putExtra(ReturnAreaEditorActivity.EXTRA_QUIET,quiet);if(a!=null)i.putExtra("area",a.id);activity.startActivity(i);}
    private void chooseHome(){
        HomeStation home=LastTrainStore.home(activity);
        double lat=home==null?StationRepository.IKEBUKURO.latitude:home.latitude,lon=home==null?StationRepository.IKEBUKURO.longitude:home.longitude;
        if(home==null)try{lat=Double.parseDouble(LastTrainStore.prefs(activity).getString("fixLat",Double.toString(lat)));
            lon=Double.parseDouble(LastTrainStore.prefs(activity).getString("fixLon",Double.toString(lon)));}catch(NumberFormatException ignored){}
        StationPickerDialog picker=new StationPickerDialog(activity,"家の最寄り駅",lat,lon,entry->{
            if(!LastTrainStore.saveHome(activity,new HomeStation(entry))){Toast.makeText(activity,"保存できない",Toast.LENGTH_SHORT).show();return false;}
            changed();return true;
        });
        if(home!=null)picker.setCurrentStation(home.name,home.lines,()->{
            AlertDialog d=new AlertDialog.Builder(activity).setTitle("家の最寄り駅を削除？").setNegativeButton("戻る",null).setPositiveButton("削除",(dialog,which)->{
                if(LastTrainStore.deleteHome(activity)){picker.dismiss();changed();}
            }).show();DesignSystem.styleDialog(d,true);
        });
        picker.show();
    }
    private void changed(){LastTrainCoordinator.stopActive(activity,false);LastTrainCoordinator.stopWatch(activity);
        LastTrainAlarms.cancel(activity);LastTrainSearchJob.cancel(activity);LastTrainCoordinator.configure(activity);refresh();}
    private void timing(){String[] labels={"5分前","10分前","15分前","20分前","30分前","45分前","60分前"};int[] values={5,10,15,20,30,45,60};
        AlertDialog d=new AlertDialog.Builder(activity).setItems(labels,(dialog,index)->{LastTrainStore.minutes(activity,values[index]);LastTrainCoordinator.restore(activity);refresh();}).show();DesignSystem.styleDialog(d,false);}
}
