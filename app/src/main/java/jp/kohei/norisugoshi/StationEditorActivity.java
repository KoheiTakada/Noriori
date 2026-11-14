package jp.kohei.norisugoshi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.HorizontalScrollView;

import java.util.List;
import java.util.ArrayList;

public final class StationEditorActivity extends Activity {
    public static final String EXTRA_STATION_ID = "station_id";
    public static final String EXTRA_EDIT_ARM = "edit_arm";
    private Station original;
    private String stationId;
    private String resolvedName;
    private double latitude;
    private double longitude;
    private boolean hasLocation;
    private boolean saving;
    private StationPickerDialog placePicker;
    private Button chooseStationButton;
    private StationMapView map;
    private Button saveButton;
    private TextView rangeLabel;
    private SeekBar rangeBar;
    private boolean syncingRangeBar;
    private final List<ActivationArea> areas = new ArrayList<>();
    private boolean editingArm;
    private boolean updatingMap;
    private int selectedArea;
    private double notificationRadius;
    private double draftArmRadius = 500;
    private TextView meaning;
    private Button notificationTab;
    private Button armTab;
    private LinearLayout areaChips;
    private HorizontalScrollView areaScroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DesignSystem.window(this);
        String id = getIntent().getStringExtra(EXTRA_STATION_ID);
        original = StationRepository.findById(this, id);
        stationId = original == null ? StationRepository.newId() : original.id;
        resolvedName = original == null ? "" : original.name;
        latitude = original == null ? StationRepository.IKEBUKURO.latitude : original.latitude;
        longitude = original == null ? StationRepository.IKEBUKURO.longitude : original.longitude;
        hasLocation = original != null;
        if (original != null) areas.addAll(original.activationAreas);
        notificationRadius = original == null ? 125 : original.alertRadiusMeters;
        editingArm = getIntent().getBooleanExtra(EXTRA_EDIT_ARM, false);
        if (state != null) {
            stationId = state.getString("id", stationId);
            resolvedName = state.getString("name", resolvedName);
            latitude = state.getDouble("lat", latitude);
            longitude = state.getDouble("lon", longitude);
            hasLocation = state.getBoolean("located", hasLocation);
            notificationRadius = state.getDouble("alert", notificationRadius);
            editingArm = state.getBoolean("editArm", editingArm);
            selectedArea = state.getInt("selectedArea");
            draftArmRadius = state.getDouble("draftArmRadius", 500);
            if (state.containsKey("areas")) restoreAreas(state.getString("areas"));
        }
        setContentView(buildScreen(state));
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::requestClose);
        }
    }

    private View buildScreen(Bundle state) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesignTokens.Color.CANVAS);
        Ui.applyInsets(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        ImageButton close = Ui.iconButton(this, R.drawable.ic_close,
                "閉じる", this::requestClose);
        toolbar.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = Ui.text(this, (original == null ? "駅を追加" : original.name) + " ▾", DesignTokens.Type.STATION, true);
        title.setContentDescription("駅を切り替える");
        title.setOnClickListener(view -> showStationPicker(title));
        title.setBackground(DesignSystem.ripple(this, Color.TRANSPARENT, DesignTokens.Shape.SMALL, Ui.BLUE));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);
        if (original != null) {
            toolbar.addView(Ui.iconButton(this, R.drawable.ic_delete,
                    "駅を削除", this::confirmDelete),
                    new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        }
        root.addView(toolbar);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        notificationTab = Ui.button(this, "通知する駅", false, () -> selectMode(false));
        armTab = Ui.button(this, "オンにする場所", false, () -> selectMode(true));
        notificationTab.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        armTab.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        tabs.addView(notificationTab, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -2, 1);
        tabParams.setMargins(Ui.dp(this, 8), 0, 0, 0);
        tabs.addView(armTab, tabParams);
        FrameLayout tabsFrame = new FrameLayout(this);
        tabsFrame.addView(tabs, new FrameLayout.LayoutParams(DesignSystem.wide(this)
                ? Ui.dp(this, DesignTokens.Size.CONTENT_MAX) : -1, -2, Gravity.CENTER_HORIZONTAL));
        areaScroll = new HorizontalScrollView(this);
        areaScroll.setHorizontalScrollBarEnabled(false);
        areaChips = new LinearLayout(this);
        areaChips.setPadding(0, 0, 0, Ui.dp(this, 8));
        areaScroll.addView(areaChips);

        FrameLayout mapFrame = new FrameLayout(this);
        map = new StationMapView(this);
        map.setStation(latitude, longitude, resolvedName, hasLocation);
        RadiusSettings.Values radii = RadiusSettings.automaticForAlertRadius(
                notificationRadius);
        map.setRadii(radii.alertMeters, radii.rearmMeters, radii.monitorMeters);
        map.setEditable(true);
        map.setFocusedRing(RadiusSettings.Ring.ALERT);
        mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1));

        chooseStationButton = Ui.button(this, "駅を選ぶ", true, this::chooseNotificationStation);
        FrameLayout.LayoutParams chooseParams = new FrameLayout.LayoutParams(-1, Ui.dp(this, 48), Gravity.TOP);
        chooseParams.setMargins(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);

        LinearLayout mapControls = new LinearLayout(this);
        mapControls.setOrientation(LinearLayout.VERTICAL);
        mapControls.setBackground(Ui.background(this, Color.WHITE, DesignTokens.Shape.PILL));
        mapControls.setElevation(Ui.dp(this, 2));
        mapControls.addView(Ui.iconButton(this, R.drawable.ic_add, "拡大", () -> map.zoomBy(1)),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        mapControls.addView(Ui.iconButton(this, R.drawable.ic_minus, "縮小", () -> map.zoomBy(-1)),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        mapControls.addView(Ui.iconButton(this, R.drawable.ic_center,
                "駅に戻る", map::fitCircle),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        controlsParams.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 32));
        mapFrame.addView(mapControls, controlsParams);
        LinearLayout body = new LinearLayout(this);
        boolean wide = DesignSystem.wide(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.addView(mapFrame, wide ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.BOTTOM);
        sheet.setBackground(Ui.background(this, Color.WHITE, DesignTokens.Shape.LARGE));
        sheet.setPadding(Ui.dp(this, DesignTokens.Space.XL), Ui.dp(this, DesignTokens.Space.XL),
                Ui.dp(this, DesignTokens.Space.XL), Ui.dp(this, DesignTokens.Space.XL));
        sheet.addView(tabsFrame);
        sheet.addView(areaScroll);
        sheet.addView(chooseStationButton, Ui.margins(this, 8, 8));
        meaning = Ui.text(this, "ここに入ったら通知", DesignTokens.Type.BODY, true);
        sheet.addView(meaning);
        rangeLabel = Ui.text(this, "", 14, false);
        rangeLabel.setTextColor(Ui.MUTED);
        sheet.addView(rangeLabel, Ui.margins(this, 6, 0));
        rangeBar = new SeekBar(this);
        DesignSystem.slider(rangeBar);
        rangeBar.setMax(1000);
        rangeBar.setContentDescription("円の大きさ");
        sheet.addView(rangeBar, Ui.margins(this, 6, 2));
        LinearLayout endpoints = new LinearLayout(this);
        TextView near = Ui.text(this, "近く", 12, false);
        TextView far = Ui.text(this, "広く", 12, false);
        near.setTextColor(Ui.MUTED);
        far.setTextColor(Ui.MUTED);
        far.setGravity(Gravity.END);
        endpoints.addView(near, new LinearLayout.LayoutParams(0, -2, 1));
        endpoints.addView(far, new LinearLayout.LayoutParams(0, -2, 1));
        sheet.addView(endpoints);

        saveButton = Ui.button(this, "保存", true, this::saveStation);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f);
        actions.addView(saveButton, saveParams);
        sheet.addView(actions, Ui.margins(this, 16, 0));
        android.widget.ScrollView sheetScroll = new android.widget.ScrollView(this);
        sheetScroll.setFillViewport(wide);
        sheetScroll.setVerticalScrollBarEnabled(false);
        sheetScroll.addView(sheet);
        body.addView(sheetScroll, wide ? new LinearLayout.LayoutParams(Ui.dp(this, 320), -1)
                : new LinearLayout.LayoutParams(-1, -2));

        rangeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !syncingRangeBar) {
                    double position = progress / 1000.0;
                    map.setFocusedRadius(map.getFocusedMinimum()
                            + (map.getFocusedMaximum() - map.getFocusedMinimum()) * position * position);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                map.beginRadiusGesture();
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                map.endRadiusGesture();
            }
        });
        map.setOnRadiiChangedListener(values -> {
            if (!updatingMap) {
                if (editingArm) {
                    draftArmRadius = values.alertMeters;
                    if (selectedArea < areas.size()) {
                        ActivationArea old = areas.get(selectedArea);
                        areas.set(selectedArea, new ActivationArea(old.name, old.latitude, old.longitude, draftArmRadius));
                    }
                } else notificationRadius = values.alertMeters;
            }
            refreshRangeUi();
        });
        updateSelectedPlace();
        refreshRangeUi();
        return root;
    }

    private void refreshRangeUi() {
        double radius = map.getFocusedRadius();
        rangeLabel.setVisibility(editingArm ? View.GONE : View.VISIBLE);
        rangeLabel.setText(editingArm ? (selectedArea < areas.size() ? areas.get(selectedArea).name : "隣駅などを選ぶ")
                : radius <= 300 ? "駅の直前" : radius <= 700 ? "駅の少し手前" : "早めに通知");
        double min = map.getFocusedMinimum();
        double max = map.getFocusedMaximum();
        syncingRangeBar = true;
        rangeBar.setProgress((int) Math.round(Math.sqrt(
                (radius - min) / Math.max(1.0, max - min)) * 1000.0));
        syncingRangeBar = false;
        saveButton.setText(editingArm && areas.isEmpty() ? "場所を追加" : "保存");
        saveButton.setEnabled(!saving && hasLocation && (areas.isEmpty() || isDraftValid()));
    }

    private void selectMode(boolean arm) {
        if (saving) return;
        editingArm = arm;
        updateSelectedPlace();
    }

    private void updateSelectedPlace() {
        updatingMap = true;
        ActivationArea selected = selectedArea < areas.size() ? areas.get(selectedArea) : null;
        if (selected != null) draftArmRadius = selected.radiusMeters;
        double radius = editingArm ? selected == null ? draftArmRadius : selected.radiusMeters : notificationRadius;
        RadiusSettings.Values values = RadiusSettings.automaticForAlertRadius(radius);
        map.setRadii(values.alertMeters, values.rearmMeters, values.monitorMeters);
        map.setStation(editingArm && selected != null ? selected.latitude : latitude,
                editingArm && selected != null ? selected.longitude : longitude,
                editingArm ? selected == null ? "場所を選ぶ" : selected.name : resolvedName,
                hasLocation);
        List<ActivationArea> refs = new ArrayList<>();
        if (editingArm && hasLocation) {
            refs.add(new ActivationArea(resolvedName, latitude, longitude, notificationRadius));
        }
        for (int i = 0; i < areas.size(); i++) if (!editingArm || i != selectedArea) refs.add(areas.get(i));
        map.setReferenceAreas(refs);
        map.setFocusedCircleId(editingArm && selected != null ? selected.id : "notify");
        map.setCircleVisible(!editingArm || selected != null);
        map.setOnCircleTappedListener(this::selectCircle);
        map.setOnLocationPickedListener(editingArm ? (lat, lon) -> {
            if (saving) return;
            draftArmRadius = 500;
            setArmPlace("場所 " + (areas.size() + 1), lat, lon);
        } : null);
        chooseStationButton.setVisibility(!editingArm && original == null ? View.VISIBLE : View.GONE);
        meaning.setText(editingArm ? "ここに入ったらオン" : "ここに入ったら通知");
        for (Button tab : new Button[]{notificationTab, armTab}) {
            boolean active = (tab == armTab) == editingArm;
            DesignSystem.selection(tab, active);
        }
        refreshAreaChips();
        updatingMap = false;
        refreshRangeUi();
    }

    private void refreshAreaChips() {
        areaScroll.setVisibility(editingArm ? View.VISIBLE : View.GONE);
        areaChips.removeAllViews();
        for (int i = 0; i < areas.size(); i++) {
            final int index = i;
            Button chip = Ui.button(this, areas.get(i).name, false, () -> {
                if (saving) return;
                if(selectedArea == index){
                    areas.remove(index);
                    // Do not arm deletion on the next chip merely because its index shifted.
                    selectedArea = areas.size();
                }else selectedArea = index;
                updateSelectedPlace();
            });
            chip.setMinWidth(0);
            chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            DesignSystem.selection(chip, i == selectedArea);
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, i == selectedArea ? R.drawable.ic_close : 0, 0);
            chip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(DesignTokens.Color.ON_PRIMARY_CONTAINER));
            chip.setCompoundDrawablePadding(Ui.dp(this, 8));
            chip.setContentDescription(areas.get(i).name + (i == selectedArea ? "を削除" : "の円を編集"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, DesignTokens.Size.TOUCH));
            params.setMargins(0, 0, Ui.dp(this, 8), 0);
            areaChips.addView(chip, params);
        }
        Button add = Ui.button(this, "＋ 追加", false, () -> {
            if (saving) return;
            chooseActivationStation();
        });
        add.setContentDescription("オンにする場所を追加");
        add.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, DesignTokens.Size.TOUCH));
        addParams.setMargins(0, 0, Ui.dp(this, 8), 0);
        areaChips.addView(add, addParams);
        if(selectedArea < areas.size())areaScroll.post(()->{
            if(isFinishing()||isDestroyed()||selectedArea >= areas.size())return;
            View selected=areaChips.getChildAt(selectedArea);
            int left=areaScroll.getScrollX(),right=left+areaScroll.getWidth();
            if(selected.getLeft()<left)areaScroll.smoothScrollTo(selected.getLeft(),0);
            else if(selected.getRight()>right)areaScroll.smoothScrollTo(selected.getRight()-areaScroll.getWidth(),0);
        });
    }

    private boolean setArmPlace(String name, double lat, double lon) {
        double distance = Geo.distanceMeters(lat, lon, latitude, longitude);
        double available = distance - notificationRadius - RadiusSettings.MIN_GAP_METERS - 1;
        if (available < 100) {
            Toast.makeText(this, "通知する円と重なる", Toast.LENGTH_SHORT).show();
            return false;
        }
        for (int i = 0; i < areas.size(); i++) {
            ActivationArea existing = areas.get(i);
            if (existing.name.equals(name) && Geo.distanceMeters(lat, lon, existing.latitude, existing.longitude) < 100) {
                selectedArea = i;
                editingArm = true;
                updateSelectedPlace();
                return true;
            }
        }
        ActivationArea area = new ActivationArea(name, lat, lon, Math.min(draftArmRadius, available));
        areas.add(area);
        selectedArea = areas.size() - 1;
        editingArm = true;
        updateSelectedPlace();
        return true;
    }

    private void chooseActivationStation() {
        if (saving || (placePicker != null && placePicker.isShowing())) return;
        placePicker = new StationPickerDialog(this, "オンにする場所を追加",
                map.getCenterLatitude(), map.getCenterLongitude(), candidate ->
                    Geo.distanceMeters(candidate.latitude, candidate.longitude, latitude, longitude)
                            > notificationRadius + RadiusSettings.MIN_GAP_METERS + 101,
                candidate -> {
            if (isFinishing() || isDestroyed()) return false;
            draftArmRadius = 500;
            return setArmPlace(candidate.name, candidate.latitude, candidate.longitude);
        });
        placePicker.show();
    }

    private void chooseNotificationStation() {
        if (saving || original != null || (placePicker != null && placePicker.isShowing())) return;
        placePicker = new StationPickerDialog(this, "通知する駅を追加",
                map.getCenterLatitude(), map.getCenterLongitude(), candidate -> {
            if (isFinishing() || isDestroyed() || original != null) return false;
            resolvedName = candidate.name;
            latitude = candidate.latitude;
            longitude = candidate.longitude;
            hasLocation = true;
            editingArm = false;
            updateSelectedPlace();
            return true;
        });
        placePicker.show();
    }

    private boolean isDraftValid() {
        if (!hasLocation || areas.isEmpty()) return false;
        Station draft = new Station(stationId, resolvedName, latitude, longitude,
                notificationRadius, 1_500, 5_000, true, areas);
        return draft.isConfigured();
    }

    private void showStationPicker(View anchor) {
        if (saving) {
            return;
        }
        List<Station> stations = StationRepository.getAll(this);
        PopupMenu picker = new PopupMenu(this, anchor);
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            picker.getMenu().add(0, i, i, station.name)
                    .setCheckable(true).setChecked(original != null && station.id.equals(original.id));
        }
        picker.getMenu().add(1, stations.size(), stations.size(), "＋ 駅を追加");
        picker.setOnMenuItemClickListener(item -> {
            Station selected = item.getItemId() < stations.size() ? stations.get(item.getItemId()) : null;
            if (selected != null && original != null && selected.id.equals(original.id)) {
                return true;
            }
            requestStationSwitch(selected);
            return true;
        });
        picker.show();
    }

    private void requestStationSwitch(Station selected) {
        if (saving) {
            return;
        }
        Runnable switchStation = () -> {
            Intent editor = new Intent(this, StationEditorActivity.class);
            if (selected != null) {
                editor.putExtra(EXTRA_STATION_ID, selected.id);
            }
            startActivity(editor);
            finish();
        };
        if (hasChanges()) {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("変更を保存せず切り替える？")
                    .setNegativeButton("編集を続ける", null)
                    .setPositiveButton("切り替える", (modal, which) -> switchStation.run()).show();
            DesignSystem.styleDialog(dialog, false);
        } else {
            switchStation.run();
        }
    }

    private void saveStation() {
        if (saving) {
            return;
        }
        if (!hasLocation) { chooseNotificationStation(); return; }
        if (areas.isEmpty()) {
            if (editingArm) chooseActivationStation(); else selectMode(true);
            return;
        }
        RadiusSettings.Values values = RadiusSettings.automaticForAlertRadius(notificationRadius);
        Station updated = new Station(stationId, resolvedName, latitude, longitude,
                values.alertMeters, values.rearmMeters, values.monitorMeters,
                true, areas);
        if (!updated.isConfigured()) {
            Toast.makeText(this, "通知する円と重なっている場所がある", Toast.LENGTH_LONG).show();
            return;
        }
        saving = true;
        saveButton.setEnabled(false);
        saveButton.setText("保存中");
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean saved = StationRepository.upsertConfirmed(app, updated);
            if (saved) {
                MonitorStateStore.setArmed(app, updated, false);
                applyStationChanges(app, updated.id);
                DetectionLogStore.append(app, updated.name + "の通知設定を保存");
            }
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                saving = false;
                saveButton.setEnabled(true);
                saveButton.setText("保存");
                if (!saved) {
                    Toast.makeText(this, "保存できません。もう一度試してください", Toast.LENGTH_LONG).show();
                    return;
                }
                setResult(RESULT_OK);
                Toast.makeText(this, "保存した", Toast.LENGTH_SHORT).show();
                finish();
            });
        }, "station-save").start();
    }

    private static void applyStationChanges(Context context, String id) {
        Station active = MonitorStateStore.getActiveStation(context);
        if (active != null && active.id.equals(id)) {
            TravelMonitorService.stopAll(context);
        }
        if (PermissionUtils.canProtect(context)) {
            GeofenceRegistrar.refresh(context);
        }
    }

    private void confirmDelete() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(resolvedName + "を削除？")
                .setNegativeButton("戻る", null)
                .setPositiveButton("削除", (modal, which) -> {
                    applyStationChanges(this, stationId);
                    StationRepository.delete(this, stationId);
                    GeofenceRegistrar.resetRegistrationState();
                    setResult(RESULT_OK);
                    finish();
                }).show();
        DesignSystem.styleDialog(dialog, true);
    }

    private void requestClose() {
        if (saving) {
            return;
        }
        if (hasChanges()) {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("変更を保存せず閉じる？")
                    .setNegativeButton("編集を続ける", null)
                    .setPositiveButton("閉じる", (modal, which) -> finish()).show();
            DesignSystem.styleDialog(dialog, false);
        } else {
            finish();
        }
    }

    private boolean hasChanges() {
        return !resolvedName.equals(original == null ? "" : original.name)
                || latitude != (original == null ? StationRepository.IKEBUKURO.latitude : original.latitude)
                || longitude != (original == null ? StationRepository.IKEBUKURO.longitude : original.longitude)
                || notificationRadius != initialAlert()
                || !sameAreas(areas, original == null ? java.util.Collections.emptyList() : original.activationAreas);
    }

    private double initialAlert() { return original == null ? 125.0 : original.alertRadiusMeters; }

    private void selectCircle(String id) {
        if (saving) return;
        if ("notify".equals(id)) {
            editingArm = false; updateSelectedPlace(); return;
        }
        for (int i = 0; i < areas.size(); i++) {
            if (areas.get(i).id.equals(id)) {
                selectedArea = i; editingArm = true; updateSelectedPlace(); return;
            }
        }
        ActivationArea notification = new ActivationArea(resolvedName, latitude, longitude, notificationRadius);
        if (notification.id.equals(id)) { editingArm = false; updateSelectedPlace(); }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("id", stationId);
        state.putString("name", resolvedName);
        state.putDouble("lat", latitude);
        state.putDouble("lon", longitude);
        state.putBoolean("located", hasLocation);
        state.putDouble("alert", notificationRadius);
        state.putBoolean("editArm", editingArm);
        state.putInt("selectedArea", selectedArea);
        state.putDouble("draftArmRadius", draftArmRadius);
        state.putString("areas", encodeAreas());
    }

    private static boolean sameAreas(List<ActivationArea> a, List<ActivationArea> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }

    private String encodeAreas() {
        org.json.JSONArray data = new org.json.JSONArray();
        try {
            for (ActivationArea area : areas) data.put(new org.json.JSONObject().put("name", area.name)
                    .put("lat", area.latitude).put("lon", area.longitude).put("radius", area.radiusMeters));
        } catch (org.json.JSONException exception) { throw new IllegalStateException(exception); }
        return data.toString();
    }

    private void restoreAreas(String encoded) {
        try {
            org.json.JSONArray data = new org.json.JSONArray(encoded);
            areas.clear();
            for (int i = 0; i < data.length(); i++) {
                org.json.JSONObject area = data.getJSONObject(i);
                areas.add(new ActivationArea(area.getString("name"), area.getDouble("lat"),
                        area.getDouble("lon"), area.getDouble("radius")));
            }
        } catch (org.json.JSONException exception) { throw new IllegalStateException(exception); }
    }

    @Override
    protected void onDestroy() {
        if (placePicker != null) placePicker.dismiss();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        requestClose();
    }
}
