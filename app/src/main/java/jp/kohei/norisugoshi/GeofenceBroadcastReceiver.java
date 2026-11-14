package jp.kohei.norisugoshi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingEvent;

import java.util.List;

public final class GeofenceBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        boolean reset=NightReset.check(appContext);
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) {
            DetectionLogStore.append(appContext, "見守り範囲の情報を読み取れませんでした");
            return;
        }
        if (event.hasError()) {
            DetectionLogStore.append(
                    appContext,
                    "見守り範囲エラー: "
                            + GeofenceStatusCodes.getStatusCodeString(event.getErrorCode())
            );
            return;
        }
        android.location.Location fix=event.getTriggeringLocation();
        if((fix==null&&reset)||(fix!=null&&fix.getTime()>0&&fix.getTime()<NightReset.lastResetAt(appContext)))return;

        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER
                || !PermissionUtils.canProtect(appContext)) return;
        List<Geofence> triggered = event.getTriggeringGeofences();
        if (triggered == null) {
            return;
        }

        for (Geofence geofence : triggered) {
            String requestId = geofence.getRequestId();
            Station station = GeofenceRegistrar.stationFromRequestId(appContext, requestId);
            if (station == null || !station.enabled || !station.isConfigured()) {
                continue;
            }

            if (GeofenceRegistrar.isActivationId(requestId)) {
                MonitorStateStore.setArmed(appContext, station, true);
                DetectionLogStore.append(
                        appContext,
                        "設定した場所に入った: " + station.name + "の通知をオン"
                );
                onApproach(appContext, station);
                continue;
            }
            if (GeofenceRegistrar.isNotificationId(requestId)) {
                if (MonitorStateStore.isArmed(appContext, station)
                        && !MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(appContext))) {
                    TravelMonitorService.startArrivalAlarm(appContext, station.id);
                }
                continue;
            }
            if (GeofenceRegistrar.isApproachId(requestId)) {
                onApproach(appContext, station);
            }
        }
    }

    private void onApproach(Context context, Station station) {
        if (!PermissionUtils.canProtect(context)) {
            DetectionLogStore.append(context, station.name + "接近を無視: 権限または設定不足");
            return;
        }
        Station activeStation = MonitorStateStore.getActiveStation(context);
        String activeMode = MonitorStateStore.getActiveMode(context);
        if (MonitorStateStore.MODE_ALERTING.equals(activeMode)) return;
        if (activeStation != null
                && activeStation.id.equals(station.id)
                && !MonitorStateStore.MODE_NONE.equals(activeMode)) {
            return;
        }

        DetectionLogStore.append(
                context,
                station.name + "の" + formatMeters(station.monitorRadiusMeters) + "圏へ進入"
        );
        TravelMonitorService.startMonitoring(context, station.id);
    }

    private static String formatMeters(double meters) {
        if (meters >= 1_000.0 && meters % 1_000.0 == 0.0) {
            return String.format(java.util.Locale.JAPAN, "%.0fkm", meters / 1_000.0);
        }
        return String.format(java.util.Locale.JAPAN, "%.0fm", meters);
    }
}
