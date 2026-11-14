package jp.kohei.norisugoshi;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;

public final class GeofenceRegistrar {
    public static final int STATE_IDLE = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_REGISTERED = 2;
    public static final int STATE_FAILED = 3;

    private static final String PREFIX_APPROACH = "approach:";
    private static final String PREFIX_ARM_APPROACH = "arm-approach:";
    private static final String PREFIX_ARM = "arm:";
    private static final String PREFIX_NOTIFY = "notify:";
    private static final int RESPONSIVENESS_MILLIS = 30_000;
    private static int registrationState = STATE_IDLE;
    private static long registrationGeneration;

    private GeofenceRegistrar() {
    }

    @SuppressLint("MissingPermission")
    public static void register(Context context) {
        Context appContext = context.getApplicationContext();
        MonitorStateStore.migrateForReliableArming(appContext);
        if (!PermissionUtils.canProtect(appContext)) {
            DetectionLogStore.append(appContext, "見守り範囲を登録できません: 権限または設定不足");
            return;
        }

        long generation = beginRegistration();
        if (generation < 0L) {
            return;
        }

        List<Geofence> geofences = buildGeofences(appContext);
        if (geofences.isEmpty()) {
            finishRegistration(generation, STATE_FAILED);
            DetectionLogStore.append(appContext, "見守る駅がありません");
            return;
        }

        GeofencingClient client = LocationServices.getGeofencingClient(appContext);
        GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(
                        GeofencingRequest.INITIAL_TRIGGER_ENTER
                                | GeofencingRequest.INITIAL_TRIGGER_EXIT
                )
                .addGeofences(geofences)
                .build();

        try {
            client.addGeofences(request, pendingIntent(appContext))
                    .addOnSuccessListener(unused -> {
                        if (finishRegistration(generation, STATE_REGISTERED)) {
                            DetectionLogStore.append(
                                    appContext,
                                    "通知する駅とオンにする場所を登録"
                            );
                        }
                    })
                    .addOnFailureListener(error -> {
                        if (finishRegistration(generation, STATE_FAILED)) {
                            DetectionLogStore.append(
                                    appContext,
                                    "見守り範囲の登録失敗: "
                                            + error.getClass().getSimpleName()
                            );
                        }
                    });
        } catch (SecurityException exception) {
            if (finishRegistration(generation, STATE_FAILED)) {
                DetectionLogStore.append(
                        appContext,
                        "見守り範囲の登録失敗: 位置権限なし"
                );
            }
        } catch (RuntimeException exception) {
            if (finishRegistration(generation, STATE_FAILED)) {
                DetectionLogStore.append(
                        appContext,
                        "見守り範囲の登録失敗: "
                                + exception.getClass().getSimpleName()
                );
            }
        }
    }

    public static void unregister(Context context) {
        Context appContext = context.getApplicationContext();
        resetRegistrationState();
        LocationServices.getGeofencingClient(appContext)
                .removeGeofences(pendingIntent(appContext))
                .addOnCompleteListener(unused -> DetectionLogStore.append(
                        appContext,
                        "駅の見守りを解除"
                ));
    }

    public static void refresh(Context context) {
        Context appContext = context.getApplicationContext();
        long generation = beginRefresh();
        LocationServices.getGeofencingClient(appContext)
                .removeGeofences(pendingIntent(appContext))
                .addOnCompleteListener(unused -> {
                    if (finishRefresh(generation)) {
                        register(appContext);
                    }
                });
    }

    public static synchronized int getRegistrationState() {
        return registrationState;
    }

    public static synchronized void resetRegistrationState() {
        registrationGeneration++;
        registrationState = STATE_IDLE;
    }

    private static synchronized long beginRefresh() {
        registrationGeneration++;
        registrationState = STATE_PENDING;
        return registrationGeneration;
    }

    private static synchronized boolean finishRefresh(long generation) {
        if (generation != registrationGeneration) {
            return false;
        }
        registrationState = STATE_IDLE;
        return true;
    }

    private static synchronized long beginRegistration() {
        if (registrationState != STATE_IDLE) {
            return -1L;
        }
        registrationState = STATE_PENDING;
        return ++registrationGeneration;
    }

    private static synchronized boolean finishRegistration(
            long generation,
            int finalState
    ) {
        if (generation != registrationGeneration
                || registrationState != STATE_PENDING) {
            return false;
        }
        registrationState = finalState;
        return true;
    }

    private static List<Geofence> buildGeofences(Context context) {
        List<Geofence> geofences = new ArrayList<>();
        for (Station station : StationRepository.getConfiguredEnabled(context)) {
            geofences.add(region(PREFIX_APPROACH + station.id, station.latitude, station.longitude,
                    station.monitorRadiusMeters));
            for (ActivationArea area : station.activationAreas) {
                geofences.add(region(PREFIX_ARM_APPROACH + station.id + ":" + area.id, area.latitude, area.longitude,
                        Math.max(5_000.0, area.radiusMeters + 2_000.0)));
                geofences.add(region(PREFIX_ARM + station.id + ":" + area.id,
                        area.latitude, area.longitude, area.radiusMeters));
            }
            geofences.add(region(PREFIX_NOTIFY + station.id, station.latitude, station.longitude,
                    station.alertRadiusMeters));
        }
        return geofences;
    }

    private static Geofence region(String id, double latitude, double longitude, double radius) {
        return new Geofence.Builder().setRequestId(id)
                .setCircularRegion(latitude, longitude, (float) radius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setNotificationResponsiveness(RESPONSIVENESS_MILLIS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).build();
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, GeofenceBroadcastReceiver.class);
        intent.setAction("jp.kohei.norisugoshi.GEOFENCE_EVENT");
        return PendingIntent.getBroadcast(
                context,
                4100,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
    }

    public static boolean isApproachId(String requestId) {
        return requestId != null && (requestId.startsWith(PREFIX_APPROACH)
                || requestId.startsWith(PREFIX_ARM_APPROACH));
    }

    public static boolean isActivationId(String requestId) {
        return requestId != null && requestId.startsWith(PREFIX_ARM);
    }

    public static boolean isNotificationId(String requestId) {
        return requestId != null && requestId.startsWith(PREFIX_NOTIFY);
    }

    public static Station stationFromRequestId(Context context, String requestId) {
        if (requestId == null) {
            return null;
        }
        if (!isApproachId(requestId) && !isActivationId(requestId) && !isNotificationId(requestId)) return null;
        String[] parts = requestId.split(":", 3);
        Station station = StationRepository.findById(context, parts[1]);
        if (station == null) return null;
        if (parts.length == 3) {
            for (ActivationArea area : station.activationAreas) if (area.id.equals(parts[2])) return station;
            return null;
        }
        return isActivationId(requestId) ? null : station;
    }
}
