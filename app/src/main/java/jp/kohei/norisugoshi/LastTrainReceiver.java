package jp.kohei.norisugoshi;
import android.content.*;
import android.location.Location;
import com.google.android.gms.location.*;
final public class LastTrainReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        String action=i==null?"":i.getAction();
        if(NightReset.ACTION.equals(action)){NightReset.configure(c);return;}
        if("stop".equals(action)){LastTrainCoordinator.stopActive(c,true);TravelMonitorService.stopAll(c);return;}
        if("alarm".equals(action)){LastTrainCoordinator.evaluate(c);return;}
        if("watch_end".equals(action)){LastTrainCoordinator.stopWatch(c);return;}
        if("area".equals(action)){
            GeofencingEvent event=GeofencingEvent.fromIntent(i);
            if(event!=null&&!event.hasError()) {
                if(event.getTriggeringLocation()!=null)LastTrainCoordinator.observe(c,event.getTriggeringLocation());
                else LastTrainCoordinator.requestFix(c);
            }
        }else if("location".equals(action)){
            LocationResult result=LocationResult.extractResult(i);
            if(result!=null&&result.getLastLocation()!=null)LastTrainCoordinator.observe(c,result.getLastLocation());
        }else LastTrainCoordinator.configure(c);
    }
}
