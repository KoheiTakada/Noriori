package jp.kohei.norisugoshi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public final class TravelMonitorService extends Service {
    public static final String ACTION_START_MONITOR =
            "jp.kohei.norisugoshi.action.START_MONITOR";
    public static final String ACTION_ARRIVAL_ALERT = "jp.kohei.norisugoshi.action.ARRIVAL_ALERT";
    public static final String ACTION_STOP_ALARM =
            "jp.kohei.norisugoshi.action.STOP_ALARM";
    public static final String ACTION_STOP_ALL =
            "jp.kohei.norisugoshi.action.STOP_ALL";
    public static final String EXTRA_STATION_ID = "station_id";

    private static final String CHANNEL_MONITORING = "approach_monitoring_v1";
    private static final String CHANNEL_ALERT = "arrival_alerts_v1";
    private static final int NOTIFICATION_ID = 4301;
    private static final long LOCATION_INTERVAL_MILLIS = 5_000L;
    private static final long MIN_LOCATION_INTERVAL_MILLIS = 2_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = this::handleTimeout;

    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private NotificationManager notificationManager;
    private Vibrator vibrator;

    private Station activeStation;
    private String currentMode = MonitorStateStore.MODE_NONE;
    private Double previousDistanceMeters;
    private boolean hasSeenOutsideAlertRadius;

    @Override
    public void onCreate() {
        super.onCreate();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        notificationManager = getSystemService(NotificationManager.class);
        vibrator = resolveVibrator();
        createNotificationChannels();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) {
                    onLocation(location);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NightReset.check(this);
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP_ALARM.equals(action) || ACTION_STOP_ALL.equals(action)) {
            RideCoordinator.dismiss(this);
            if (LastTrainStore.alerting(this)) LastTrainCoordinator.stopActive(this, true);
            stopEverything("利用者が停止");
            return START_NOT_STICKY;
        }

        if(MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(this))){
            Station stored=MonitorStateStore.getActiveStation(this);
            if(stored!=null&&stored.enabled&&stored.isConfigured()&&PermissionUtils.canProtect(this)){
                if(!MonitorStateStore.MODE_ALERTING.equals(currentMode))beginAlarm(stored,"通知を復元");
                return START_REDELIVER_INTENT;
            }
            cancelCurrent(this);return START_NOT_STICKY;
        }

        if (ACTION_ARRIVAL_ALERT.equals(action)) {
            if (MonitorStateStore.MODE_ALERTING.equals(currentMode)) return START_REDELIVER_INTENT;
            Station station = stationFromIntent(intent, null);
            if (!MonitorStateStore.MODE_ALERTING.equals(currentMode) && station != null
                    && station.enabled && station.isConfigured() && MonitorStateStore.isArmed(this, station)
                    && PermissionUtils.canProtect(this)) {
                beginAlarm(station, "通知する円へ進入");
                return START_REDELIVER_INTENT;
            }
            if (MonitorStateStore.MODE_NONE.equals(currentMode)) { stopSelf(); return START_NOT_STICKY; }
            return START_REDELIVER_INTENT;
        }

        if (ACTION_START_MONITOR.equals(action)) {
            if (MonitorStateStore.MODE_ALERTING.equals(currentMode)) return START_REDELIVER_INTENT;
            Station station = stationFromIntent(intent, null);
            if (station == null || !station.enabled || !station.isConfigured()) {
                DetectionLogStore.append(this, "見守りを開始できません: 駅IDなし");
                stopSelf();
                return START_NOT_STICKY;
            }
            beginMonitoring(station, false);
            return START_REDELIVER_INTENT;
        }

        restoreActiveState();
        return START_REDELIVER_INTENT;
    }

    private Station stationFromIntent(Intent intent, Station fallback) {
        if (intent == null) {
            return fallback;
        }
        Station station = StationRepository.findById(
                this,
                intent.getStringExtra(EXTRA_STATION_ID)
        );
        return station == null ? fallback : station;
    }

    private void restoreActiveState() {
        String storedMode = MonitorStateStore.getActiveMode(this);
        Station storedStation = MonitorStateStore.getActiveStation(this);
        if (storedStation == null) {
            stopSelf();
            return;
        }
        if (MonitorStateStore.MODE_ALERTING.equals(storedMode)) {
            beginAlarm(
                    storedStation,
                    "通知を復元"
            );
        } else if (MonitorStateStore.MODE_MONITORING.equals(storedMode)) {
            beginMonitoring(storedStation, true);
        } else {
            stopSelf();
        }
    }

    private void beginMonitoring(Station station, boolean restoring) {
        if (MonitorStateStore.MODE_ALERTING.equals(currentMode)) return;
        if (!station.isConfigured()) { stopEverything("オンにする場所が未設定"); return; }
        if (!PermissionUtils.canProtect(this)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            DetectionLogStore.append(this, station.name + "の見守り失敗: 権限不足");
            stopEverything("権限不足");
            return;
        }

        stopLocationUpdates();
        cancelScheduledWork();
        AlertRepeater.stop(NOTIFICATION_ID);
        cancelVibration();

        boolean restoredSeenOutside = restoring
                && MonitorStateStore.hasActiveSeenOutside(this);
        activeStation = station;
        currentMode = MonitorStateStore.MODE_MONITORING;
        previousDistanceMeters = null;
        hasSeenOutsideAlertRadius = restoredSeenOutside;
        MonitorStateStore.setActive(
                this,
                MonitorStateStore.MODE_MONITORING,
                station
        );
        if (restoredSeenOutside) {
            MonitorStateStore.setActiveSeenOutside(this, true);
        }

        startAsForeground(buildMonitoringNotification(station));
        DetectionLogStore.append(this, station.name + "の見守りを開始");
        requestLocationUpdates();

        handler.postDelayed(
                timeoutRunnable,
                ArrivalDecisionEngine.MONITOR_TIMEOUT_MILLIS
        );
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_INTERVAL_MILLIS
        )
                .setMinUpdateIntervalMillis(MIN_LOCATION_INTERVAL_MILLIS)
                .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MILLIS)
                .setWaitForAccurateLocation(false)
                .build();
        try {
            locationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
            );
        } catch (SecurityException exception) {
            DetectionLogStore.append(this, "位置取得を開始できません: 権限不足");
            stopEverything("位置権限不足");
        }
    }

    private void onLocation(Location location) {
        if(NightReset.check(this)||(location.getTime()>0&&location.getTime()<NightReset.lastResetAt(this)))return;
        RideCoordinator.observe(this, location);
        LastTrainCoordinator.observe(this, location);
        if (!MonitorStateStore.MODE_MONITORING.equals(currentMode)
                || activeStation == null) {
            return;
        }

        double distanceMeters = Geo.distanceMeters(
                location.getLatitude(),
                location.getLongitude(),
                activeStation.latitude,
                activeStation.longitude
        );
        float accuracyMeters = location.hasAccuracy()
                ? location.getAccuracy()
                : Float.MAX_VALUE;

        DetectionLogStore.appendLocation(
                this,
                activeStation,
                location,
                distanceMeters
        );

        if (!ArrivalDecisionEngine.isAccurateEnough(accuracyMeters)) return;
        Station alert = null;
        double nearest = Double.MAX_VALUE;
        for (Station station : StationRepository.getConfiguredEnabled(this)) {
            for (ActivationArea area : station.activationAreas) {
                double areaDistance = Geo.distanceMeters(location.getLatitude(), location.getLongitude(),
                        area.latitude, area.longitude);
                if (ArrivalDecisionEngine.shouldActivate(areaDistance, accuracyMeters, area.radiusMeters)
                        && !MonitorStateStore.isArmed(this, station)) {
                    MonitorStateStore.setArmed(this, station, true);
                    DetectionLogStore.append(this, area.name + "で" + station.name + "の通知をオン");
                }
            }
            double destinationDistance = Geo.distanceMeters(location.getLatitude(), location.getLongitude(),
                    station.latitude, station.longitude);
            if (ArrivalDecisionEngine.shouldAlert(destinationDistance, accuracyMeters,
                    station.alertRadiusMeters, MonitorStateStore.isArmed(this, station))
                    && destinationDistance < nearest) {
                alert = station;
                nearest = destinationDistance;
            }
        }
        if (alert != null) {
            beginAlarm(alert, "通知する円へ進入");
            return;
        }
        boolean outsideArmMonitors = true;
        for (ActivationArea area : activeStation.activationAreas) {
            double areaDistance = Geo.distanceMeters(location.getLatitude(), location.getLongitude(),
                    area.latitude, area.longitude);
            if (areaDistance <= Math.max(5_000.0, area.radiusMeters + 2_000.0)) outsideArmMonitors = false;
        }
        if (distanceMeters > activeStation.monitorRadiusMeters && outsideArmMonitors) {
            stopEverything("2つの場所から離れた");
        }
    }

    private void handleTimeout() {
        if (MonitorStateStore.MODE_MONITORING.equals(currentMode)) {
            DetectionLogStore.append(this, "駅の見守りが20分で終了");
            stopEverything("見守りタイムアウト");
        }
    }

    private void beginAlarm(Station station, String reason) {
        RideNotification.hide(this);
        stopLocationUpdates();
        cancelScheduledWork();

        activeStation = station;
        currentMode = MonitorStateStore.MODE_ALERTING;
        MonitorStateStore.setArmed(this, station, false);
        MonitorStateStore.setActive(
                this,
                MonitorStateStore.MODE_ALERTING,
                station
        );

        startAsForeground(buildAlertNotification(station));
        AlertRepeater.start(this,NOTIFICATION_ID,()->
                MonitorStateStore.MODE_ALERTING.equals(currentMode)&&MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(this))
                        ?buildAlertNotification(station):null);
        DetectionLogStore.append(
                this,
                station.name + "の通知開始 / " + reason
        );
    }

    private void stopEverything(String reason) {
        stopLocationUpdates();
        cancelScheduledWork();
        AlertRepeater.stop(NOTIFICATION_ID);
        if (!MonitorStateStore.MODE_NONE.equals(currentMode)) {
            DetectionLogStore.append(this, "通知・見守りを停止 / " + reason);
        }
        currentMode = MonitorStateStore.MODE_NONE;
        activeStation = null;
        previousDistanceMeters = null;
        hasSeenOutsideAlertRadius = false;
        MonitorStateStore.clearActive(this);
        AlarmVibration.refresh(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        stopSelf();
    }

    private void stopLocationUpdates() {
        if (locationClient != null && locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void cancelScheduledWork() {
        handler.removeCallbacks(timeoutRunnable);
    }

    private Notification buildMonitoringNotification(Station station) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_MONITORING)
                .setSmallIcon(R.drawable.ic_noriori)
                .setColor(getColor(R.color.brand_primary))
                .setContentTitle(station.name + "を見守り中")
                .setContentText("近づいたら通知")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private Notification buildAlertNotification(Station station) {
        String title = "まもなく" + station.name + "です";
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_train),
                "停止",
                stopAlarmIntent()
        ).build();
        return new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_noriori)
                .setColor(getColor(R.color.status_warning))
                .setContentTitle(title)
                .setContentText("停止まで振動")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(stopAction)
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                4302,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent stopAlarmIntent() {
        Intent intent = new Intent(this, TravelMonitorService.class);
        intent.setAction(ACTION_STOP_ALARM);
        return PendingIntent.getService(
                this,
                4303,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void startAsForeground(Notification notification) {
        startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        );
    }

    private void createNotificationChannels() {
        if (notificationManager == null) {
            return;
        }

        NotificationChannel monitoring = new NotificationChannel(
                CHANNEL_MONITORING,
                "駅への見守り",
                NotificationManager.IMPORTANCE_LOW
        );
        monitoring.setDescription("乗り過ごしたくない駅へ近づくと位置を確認します");
        monitoring.setSound(null, null);
        monitoring.enableVibration(false);
        monitoring.setShowBadge(false);

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT,
                "到着前の通知",
                NotificationManager.IMPORTANCE_HIGH
        );
        alert.setDescription("乗り過ごしたくない駅への到着前に通知します");
        alert.setSound(null, null);
        alert.enableVibration(false);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        notificationManager.createNotificationChannel(monitoring);
        notificationManager.createNotificationChannel(alert);
    }

    private Vibrator resolveVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = getSystemService(VibratorManager.class);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return getSystemService(Vibrator.class);
    }

    private void cancelVibration() {
        if (vibrator != null) {
            AlarmVibration.refresh(this);
        }
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        cancelScheduledWork();
        AlertRepeater.stop(this,NOTIFICATION_ID);
        cancelVibration();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startMonitoring(Context context, String stationId) {
        Intent intent = new Intent(context, TravelMonitorService.class);
        intent.setAction(ACTION_START_MONITOR);
        intent.putExtra(EXTRA_STATION_ID, stationId);
        context.startForegroundService(intent);
    }

    public static void startArrivalAlarm(Context context, String stationId) {
        context.startForegroundService(new Intent(context, TravelMonitorService.class)
                .setAction(ACTION_ARRIVAL_ALERT).putExtra(EXTRA_STATION_ID, stationId));
    }

    public static void stopAll(Context context) {
        RideCoordinator.dismiss(context);
        Intent intent = new Intent(context, TravelMonitorService.class);
        intent.setAction(ACTION_STOP_ALL);
        context.startService(intent);
    }

    static void restoreAlarm(Context context){
        if(MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(context))&&!AlertRepeater.isRunning(NOTIFICATION_ID)
                &&PermissionUtils.canProtect(context))context.startForegroundService(new Intent(context,TravelMonitorService.class));
    }

    static void cancelCurrent(Context context){
        AlertRepeater.stop(NOTIFICATION_ID);MonitorStateStore.clearActive(context);
        context.stopService(new Intent(context,TravelMonitorService.class));
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager!=null)manager.cancel(NOTIFICATION_ID);
        AlarmVibration.refresh(context);
    }

    private static String formatMeters(double meters) {
        if (meters >= 1_000.0 && meters % 1_000.0 == 0.0) {
            return String.format(java.util.Locale.JAPAN, "%.0fkm", meters / 1_000.0);
        }
        return String.format(java.util.Locale.JAPAN, "%.0fm", meters);
    }
}
