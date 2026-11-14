package jp.kohei.norisugoshi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private static final long UI_REFRESH_INTERVAL_MILLIS = 1_000L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            uiHandler.postDelayed(this, UI_REFRESH_INTERVAL_MILLIS);
        }
    };

    private Button fineLocationButton;
    private Button backgroundLocationButton;
    private Button notificationButton;
    private Button activityButton;
    private Button locationSettingsButton;
    private Button batteryButton;
    private Button promotionButton;
    private PermissionSetup permissionSetup;
    private LinearLayout stationsCard;
    private String renderedStationsState = "";
    private LinearLayout setupCard;
    private int selectedFeature;
    private View stationsScreen;
    private LastTrainPanel lastTrainPanel;
    private final java.util.List<LinearLayout> featureTabs = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DesignSystem.window(this);
        permissionSetup = new PermissionSetup(this, savedInstanceState);
        MonitorStateStore.migrateForReliableArming(this);
        selectedFeature = savedInstanceState == null ? (getIntent().getBooleanExtra("last_train", false) ? 1 : 0)
                : savedInstanceState.getInt("feature", 0);
        setContentView(buildScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        NightReset.check(this);
        TravelMonitorService.restoreAlarm(this);
        uiHandler.removeCallbacks(uiRefreshRunnable);
        if (GeofenceRegistrar.getRegistrationState() == GeofenceRegistrar.STATE_FAILED) {
            GeofenceRegistrar.resetRegistrationState();
        }
        refreshStationsCard();
        refreshUi();
        LastTrainCoordinator.configure(this);
        RideCoordinator.foreground=true;
        RideCoordinator.configure(this);
        RideCoordinator.probe(this);
        if (RideCoordinator.activityPermission(this) && RideCoordinator.shouldTrack(this)) RideTrackingService.start(this);
        uiHandler.postDelayed(uiRefreshRunnable, UI_REFRESH_INTERVAL_MILLIS);
        permissionSetup.resume();
    }

    @Override
    protected void onPause() {
        permissionSetup.pause();
        RideCoordinator.foreground=false;
        uiHandler.removeCallbacks(uiRefreshRunnable);
        super.onPause();
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    private View buildScreen() {
        FrameLayout shell = new FrameLayout(this);
        boolean wide = DesignSystem.wide(this);
        shell.setBackgroundColor(DesignTokens.Color.CANVAS);
        Ui.applyInsets(shell);
        FrameLayout body = new FrameLayout(this);
        stationsScreen = buildStationScreen();
        lastTrainPanel = new LastTrainPanel(this);
        body.addView(stationsScreen, new FrameLayout.LayoutParams(-1,-1));
        body.addView(lastTrainPanel, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (wide) nav.setGravity(Gravity.BOTTOM);
        nav.setBackgroundColor(DesignTokens.Color.SURFACE);
        nav.setPadding(wide ? dp(8) : dp(24), dp(8), wide ? dp(8) : dp(24), dp(8));
        String[] labels = {"降車", "終電"};
        int[] icons = {R.drawable.ic_disembark, R.drawable.ic_clock};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(dp(64));
            android.widget.ImageView icon = DesignSystem.icon(this, icons[i], Ui.BLUE, 24);
            LinearLayout iconPill = new LinearLayout(this);
            iconPill.setGravity(Gravity.CENTER);
            iconPill.addView(icon);
            tab.addView(iconPill, new LinearLayout.LayoutParams(dp(56),dp(32)));
            TextView label = Ui.text(this,labels[i],12,true);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2,-2);
            labelParams.topMargin = dp(4); tab.addView(label,labelParams);
            tab.setContentDescription(labels[i]+"タブ");
            tab.setBackground(DesignSystem.ripple(this,Color.TRANSPARENT,DesignTokens.Shape.PILL,Ui.BLUE));
            tab.setOnClickListener(v -> selectFeature(index));
            featureTabs.add(tab);
            nav.addView(tab,wide ? new LinearLayout.LayoutParams(-1,dp(76))
                    : new LinearLayout.LayoutParams(0,-1,1));
        }
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1,-1);
        if (wide) contentParams.rightMargin = dp(88);
        else contentParams.bottomMargin = dp(80);
        shell.addView(body,contentParams);
        // Keep the rail at the physical right edge, including in RTL layouts.
        shell.addView(nav,new FrameLayout.LayoutParams(wide ? dp(88) : -1,
                wide ? -1 : dp(80),wide ? Gravity.RIGHT : Gravity.BOTTOM));
        selectFeature(selectedFeature);
        return shell;
    }

    private void selectFeature(int feature) {
        selectedFeature = feature;
        stationsScreen.setVisibility(feature == 0 ? View.VISIBLE : View.GONE);
        lastTrainPanel.setVisibility(feature == 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < featureTabs.size(); i++) {
            LinearLayout tab = featureTabs.get(i);
            boolean selected = i == feature;
            tab.setSelected(selected);
            tab.getChildAt(0).setBackground(DesignSystem.shape(this,selected ? DesignTokens.Color.PRIMARY_CONTAINER
                    : Color.TRANSPARENT,DesignTokens.Shape.PILL));
            ((TextView)tab.getChildAt(1)).setTextColor(selected ? Ui.BLUE : Ui.MUTED);
            if (Build.VERSION.SDK_INT >= 30) tab.setStateDescription(selected ? "選択中" : null);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("feature",selectedFeature);
        permissionSetup.save(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        permissionSetup.result(requestCode);
    }

    void requestPermissionSetup(int step) { permissionSetup.request(step); }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("last_train",false)) selectFeature(1);
    }

    void openLogs() { showLogs(); }

    private View buildStationScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getColor(R.color.surface));
        scrollView.setVerticalScrollBarEnabled(false);

        FrameLayout frame = new FrameLayout(this);
        scrollView.addView(frame, new ScrollView.LayoutParams(-1, -1));
        LinearLayout root = new DesignSystem.ContentColumn(this);
        root.setPadding(dp(DesignTokens.Space.PAGE), dp(DesignTokens.Space.XL),
                dp(DesignTokens.Space.PAGE), dp(DesignTokens.Space.XXL));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        TextView title = DesignSystem.text(this, getString(R.string.app_name), DesignSystem.Text.TITLE);
        title.setContentDescription(getString(R.string.app_name) + "。長押しで履歴");
        title.setOnLongClickListener(view -> { showLogs(); return true; });
        root.addView(title);
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout stationHeading = new LinearLayout(this);
        stationHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = DesignSystem.text(this, "乗り過ごしたくない駅", DesignSystem.Text.LABEL);
        heading.setTextColor(DesignTokens.Color.MUTED);
        stationHeading.addView(heading,
                new LinearLayout.LayoutParams(0, -2, 1f));
        Button add = secondaryButton("＋ 追加", () -> showStationEditor(null));
        add.setMinHeight(dp(DesignTokens.Size.TOUCH));
        add.setPadding(dp(12), 0, dp(12), 0);
        add.setContentDescription("駅を追加");
        stationHeading.addView(add, new LinearLayout.LayoutParams(dp(88), dp(DesignTokens.Size.TOUCH)));
        root.addView(stationHeading, marginParams(0, DesignTokens.Space.XL, 0, 0));
        stationsCard = new LinearLayout(this);
        stationsCard.setOrientation(LinearLayout.VERTICAL);
        refreshStationsCard();
        root.addView(stationsCard, marginParams(0, 8, 0, 18));

        setupCard = card();
        fineLocationButton = button("位置情報を許可", () -> permissionSetup.request(PermissionSetup.LOCATION));
        backgroundLocationButton = button(
                "位置情報を「常に許可」",
                () -> permissionSetup.request(PermissionSetup.BACKGROUND)
        );
        notificationButton = button("通知を許可", () -> permissionSetup.request(PermissionSetup.NOTIFICATIONS));
        activityButton = button("乗車を自動で検知", () -> permissionSetup.request(PermissionSetup.ACTIVITY));
        promotionButton = button("駅名の表示を許可", () -> permissionSetup.request(PermissionSetup.PROMOTION));
        locationSettingsButton = button("位置情報をONにする", () ->
                permissionSetup.request(PermissionSetup.LOCATION_SWITCH));
        batteryButton = button("バックグラウンド動作を許可", () ->
                permissionSetup.request(PermissionSetup.BATTERY));
        setupCard.addView(fineLocationButton);
        setupCard.addView(backgroundLocationButton, marginParams(0, 8, 0, 0));
        setupCard.addView(notificationButton, marginParams(0, 8, 0, 0));
        setupCard.addView(activityButton, marginParams(0, 8, 0, 0));
        setupCard.addView(promotionButton, marginParams(0, 8, 0, 0));
        setupCard.addView(locationSettingsButton, marginParams(0, 8, 0, 0));
        setupCard.addView(batteryButton, marginParams(0, 8, 0, 0));
        root.addView(setupCard, marginParams(0, 8, 0, 18));

        return scrollView;
    }

    private void refreshUi() {
        boolean fine = PermissionUtils.hasFineLocation(this);
        boolean background = PermissionUtils.hasBackgroundLocation(this);
        boolean notification = PermissionUtils.hasNotificationPermission(this);
        boolean locationEnabled = PermissionUtils.isLocationEnabled(this);
        boolean batteryExcluded = PermissionUtils.isIgnoringBatteryOptimizations(this);
        boolean hasTargets = !StationRepository.getConfiguredEnabled(this).isEmpty();
        boolean settingsReady = PermissionUtils.canProtect(this)
                && hasTargets;

        if (settingsReady) {
            GeofenceRegistrar.register(this);
        } else {
            if (GeofenceRegistrar.getRegistrationState()
                    != GeofenceRegistrar.STATE_IDLE) {
                GeofenceRegistrar.resetRegistrationState();
            }
        }

        fineLocationButton.setVisibility(fine ? View.GONE : View.VISIBLE);
        backgroundLocationButton.setVisibility(background ? View.GONE : View.VISIBLE);
        notificationButton.setVisibility(notification ? View.GONE : View.VISIBLE);
        boolean activity = RideCoordinator.activityPermission(this);
        activityButton.setVisibility(activity ? View.GONE : View.VISIBLE);
        boolean promotion = PermissionUtils.canPostPromotedNotifications(this);
        promotionButton.setVisibility(promotion ? View.GONE : View.VISIBLE);
        locationSettingsButton.setVisibility(locationEnabled ? View.GONE : View.VISIBLE);
        batteryButton.setVisibility(batteryExcluded ? View.GONE : View.VISIBLE);
        boolean setupDone = fine
                && background
                && notification
                && activity
                && promotion
                && locationEnabled
                && batteryExcluded;
        setupCard.setVisibility(setupDone ? View.GONE : View.VISIBLE);
        if (!renderedStationsState.equals(stationsStateSnapshot())) {
            refreshStationsCard();
        }
        if (lastTrainPanel != null) lastTrainPanel.refresh();
    }

    private void copyLogs() {
        String logs = DetectionLogStore.getRecent(this, 200);
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name) + "のログ", logs));
            Toast.makeText(this, "ログをコピーしました", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLogs() {
        TextView logs = text(DetectionLogStore.getRecent(this, 200), 12, false);
        logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextIsSelectable(true);
        logs.setPadding(dp(18), dp(12), dp(18), dp(12));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logs);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNeutralButton("コピー", (modal, which) -> copyLogs())
                .setNegativeButton("消去", (modal, which) -> DetectionLogStore.clear(this))
                .setPositiveButton("閉じる", null)
                .show();
        DesignSystem.styleDialog(dialog, false);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionSetup.result(requestCode);
        refreshUi();
        LastTrainCoordinator.configure(this);
        RideCoordinator.configure(this);
        RideCoordinator.probe(this);
    }

    private void refreshStationsCard() {
        if (stationsCard == null) {
            return;
        }
        stationsCard.removeAllViews();
        List<Station> stations = StationRepository.getAll(this);
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(DesignTokens.Size.ROW));
            row.setPadding(dp(DesignTokens.Space.LG), dp(DesignTokens.Space.SM),
                    dp(DesignTokens.Space.LG), dp(DesignTokens.Space.SM));
            boolean alerting = isAlertingStation(station);
            boolean armed = MonitorStateStore.isArmed(this, station);
            row.setBackground(DesignSystem.shape(this, alerting ? DesignTokens.Color.DANGER_CONTAINER
                    : armed ? DesignTokens.Color.PRIMARY_CONTAINER : DesignTokens.Color.SURFACE,
                    DesignTokens.Shape.LARGE));
            LinearLayout labels = new LinearLayout(this);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setMinimumHeight(dp(64));
            labels.setPadding(0, dp(DesignTokens.Space.SM), dp(DesignTokens.Space.MD), dp(DesignTokens.Space.SM));
            labels.setBackground(DesignSystem.ripple(this, Color.TRANSPARENT,
                    DesignTokens.Shape.MEDIUM, DesignTokens.Color.PRIMARY));
            TextView name = DesignSystem.text(this, station.name, DesignSystem.Text.STATION);
            name.setMaxLines(2);
            labels.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            labels.addView(DesignSystem.icon(this, R.drawable.ic_chevron, DesignTokens.Color.MUTED, 20));
            labels.setOnClickListener(view -> showStationEditor(station));
            labels.setContentDescription(station.name + "の通知範囲を編集");
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            if (alerting) {
                Button stop = DesignSystem.button(this, "停止", DesignSystem.Action.DANGER, () -> {
                    TravelMonitorService.stopAll(this);
                    refreshUi();
                });
                stop.setMinWidth(0);
                stop.setPadding(0, 0, 0, 0);
                stop.setContentDescription(station.name + "の振動を止める");
                row.addView(stop, new LinearLayout.LayoutParams(dp(64), dp(48)));
            } else if (!station.isConfigured()) {
                Button configure = secondaryButton("＋", () -> showStationEditor(station));
                configure.setMinWidth(0);
                configure.setPadding(0, 0, 0, 0);
                configure.setContentDescription(station.name + "の場所を設定");
                row.addView(configure, new LinearLayout.LayoutParams(dp(64), dp(48)));
            } else {
                android.widget.Switch state = DesignSystem.stateSwitch(this, armed,
                        station.name + "の通知待ちを切り替える");
                state.setEnabled(station.isConfigured());
                state.setContentDescription(station.name + "の通知待ちを切り替える");
                state.setOnCheckedChangeListener((button, checked) -> {
                    Station current = StationRepository.findById(this, station.id);
                    if (current == null || !current.isConfigured()) return;
                    MonitorStateStore.setArmed(this, current, checked);
                    DetectionLogStore.append(this, current.name
                            + (checked ? "を通知待ちに変更" : "をオンエリア待ちに変更"));
                    if (checked && PermissionUtils.canProtect(this)) {
                        TravelMonitorService.startMonitoring(this, current.id);
                    }
                    refreshUi();
                });
                row.addView(state, new LinearLayout.LayoutParams(dp(64), dp(52)));
            }
            stationsCard.addView(row, marginParams(0, i == 0 ? 0 : DesignTokens.Space.SM, 0, 0));
        }
        if (stations.isEmpty()) {
            Button empty = secondaryButton("＋ 駅を追加", () -> showStationEditor(null));
            empty.setContentDescription("最初の駅を追加");
            stationsCard.addView(empty);
        }
        renderedStationsState = stationsStateSnapshot();
    }

    private boolean isAlertingStation(Station station) {
        Station active = MonitorStateStore.getActiveStation(this);
        return MonitorStateStore.MODE_ALERTING.equals(MonitorStateStore.getActiveMode(this))
                && active != null && active.id.equals(station.id);
    }

    private String stationsStateSnapshot() {
        StringBuilder state = new StringBuilder();
        for (Station station : StationRepository.getAll(this)) {
            state.append(station.id).append(':').append(station.name).append(':')
                    .append(station.isConfigured()).append(':')
                    .append(MonitorStateStore.isArmed(this, station)).append(':')
                    .append(isAlertingStation(station)).append(';');
        }
        return state.toString();
    }

    private void showStationEditor(Station existing) {
        android.content.Intent editor = new android.content.Intent(this, StationEditorActivity.class);
        if (existing != null) {
            editor.putExtra(StationEditorActivity.EXTRA_STATION_ID, existing.id);
            editor.putExtra(StationEditorActivity.EXTRA_EDIT_ARM, !existing.isConfigured());
        }
        startActivity(editor);
    }


    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(DesignTokens.Space.LG), dp(DesignTokens.Space.LG),
                dp(DesignTokens.Space.LG), dp(DesignTokens.Space.LG));
        layout.setBackground(DesignSystem.shape(this, DesignTokens.Color.SURFACE, DesignTokens.Shape.LARGE));
        return layout;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        return DesignSystem.text(this, value, sizeSp, bold);
    }

    private Button button(String label, Runnable action) {
        return DesignSystem.button(this, label, DesignSystem.Action.FILLED, action);
    }

    private Button secondaryButton(String label, Runnable action) {
        return DesignSystem.button(this, label, DesignSystem.Action.TONAL, action);
    }

    private LinearLayout.LayoutParams marginParams(
            int left,
            int top,
            int right,
            int bottom
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }


    private int dp(int value) {
        return DesignSystem.dp(this, value);
    }
}
