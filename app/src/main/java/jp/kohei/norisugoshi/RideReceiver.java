package jp.kohei.norisugoshi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.LocationResult;

public final class RideReceiver extends BroadcastReceiver {
    static final String MOTION="jp.kohei.norisugoshi.RIDE_MOTION",LOCATION="jp.kohei.norisugoshi.RIDE_LOCATION",
            DISMISS="jp.kohei.norisugoshi.RIDE_DISMISS",SNAPSHOT="jp.kohei.norisugoshi.RIDE_SNAPSHOT";
    @Override public void onReceive(Context context,Intent intent) {
        if (intent==null) return;
        Context c=context.getApplicationContext();
        if (DISMISS.equals(intent.getAction())) {RideCoordinator.dismiss(c);return;}
        if (SNAPSHOT.equals(intent.getAction())) {
            RideCoordinator.finishProbe(c);
            ActivityRecognitionResult result=ActivityRecognitionResult.extractResult(intent);
            if (result==null || !RideCoordinator.activityPermission(c) || !PermissionUtils.canProtect(c)) return;
            DetectedActivity activity=result.getMostProbableActivity();
            if (activity.getConfidence()<75) return;
            RideEngine.Motion motion=motion(activity.getType());
            RideCoordinator.motion(c,motion,true,result.getElapsedRealtimeMillis());
            if (motion==RideEngine.Motion.VEHICLE && RideCoordinator.foreground && RideCoordinator.shouldTrack(c)) RideTrackingService.start(c);
        } else if (MOTION.equals(intent.getAction())) {
            if (!PermissionUtils.canProtect(c) || !RideCoordinator.activityPermission(c)) return;
            ActivityTransitionResult result=ActivityTransitionResult.extractResult(intent);
            if (result==null) return;
            boolean vehicleEnter=false;
            for (ActivityTransitionEvent event:result.getTransitionEvents()) {
                RideEngine.Motion motion=motion(event.getActivityType());
                boolean enter=event.getTransitionType()==ActivityTransition.ACTIVITY_TRANSITION_ENTER;
                long time=event.getElapsedRealTimeNanos()/1_000_000;
                RideCoordinator.motion(c,motion,enter,time);
                vehicleEnter|=motion==RideEngine.Motion.VEHICLE && enter;
            }
            // An activity-transition event is a documented background-FGS start
            // exemption. Passive location delivery itself is not one.
            if (vehicleEnter && RideCoordinator.shouldTrack(c)) RideTrackingService.start(c);
        } else if (LOCATION.equals(intent.getAction())) {
            LocationResult result=LocationResult.extractResult(intent);
            if (result==null || result.getLastLocation()==null) return;
            PendingResult pending=goAsync();
            RideCoordinator.observe(c,result.getLastLocation(),pending::finish);
        }
    }
    private static RideEngine.Motion motion(int type) {
        if (type==DetectedActivity.IN_VEHICLE) return RideEngine.Motion.VEHICLE;
        if (type==DetectedActivity.WALKING || type==DetectedActivity.RUNNING) return RideEngine.Motion.WALKING;
        if (type==DetectedActivity.STILL) return RideEngine.Motion.STILL;
        if (type==DetectedActivity.ON_BICYCLE) return RideEngine.Motion.BICYCLE;
        return RideEngine.Motion.UNKNOWN;
    }
}
