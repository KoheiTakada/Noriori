package jp.kohei.norisugoshi;

import android.content.Intent;
import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.LocationManager;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScreenRenderTest {
    @Before
    public void clearSettings() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        GeofenceRegistrar.resetRegistrationState();
        PlaceFixtures.install(context);
    }

    @Test
    public void renderEditorForVisualReview() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id);
        try (ActivityController<StationEditorActivity> controller =
                     Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            StationMapView map = ReflectionHelpers.getField(controller.get(), "map");
            map.disableNetworkTilesForTesting();
            render(controller.get().findViewById(android.R.id.content), "editor");
            double metersPerPixel = ReflectionHelpers.getField(map, "lastMetersPerPixel");
            double radiusPixels = map.getFocusedRadius() / metersPerPixel;
            org.junit.Assert.assertTrue("Circle must fit the visible map",
                    radiusPixels < map.getWidth() * 0.45);
            org.junit.Assert.assertTrue("Circle must not be too small to drag",
                    radiusPixels > map.getWidth() * 0.20);
        }
    }

    @Test
    public void renderHomeForVisualReview() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            render(root, "home");
            assertStationVisible(root, "朝霞台駅");
            assertStationVisible(root, "池袋駅");
        }
    }

    @Test
    public void renderProtectedHomeForVisualReview() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        org.robolectric.Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        org.robolectric.Shadows.shadowOf(context.getSystemService(PowerManager.class))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        ReflectionHelpers.setStaticField(GeofenceRegistrar.class, "registrationState",
                GeofenceRegistrar.STATE_REGISTERED);
        MonitorStateStore.migrateForReliableArming(context);
        MonitorStateStore.setArmed(context, StationRepository.IKEBUKURO, true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            render(root, "home-protected");
            assertStationVisible(root, "朝霞台駅");
            assertStationVisible(root, "池袋駅");
        }
    }

    private void assertStationVisible(View root, String name) {
        View station = findStation(root, name + "の通知範囲を編集");
        org.junit.Assert.assertNotNull(name + " must be in the station list", station);
        org.junit.Assert.assertTrue(name + " must retain a visible touch target",
                station.getHeight() >= Ui.dp(root.getContext(), 48));
        int[] position = new int[2];
        station.getLocationOnScreen(position);
        org.junit.Assert.assertTrue(name + " must be visible without scrolling",
                position[1] >= 0 && position[1] + station.getHeight() <= root.getHeight());
    }

    private View findStation(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findStation(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void renderIkebukuroEditorForVisualReview() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id);
        try (ActivityController<StationEditorActivity> controller =
                     Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            render(controller.get().findViewById(android.R.id.content), "editor-ikebukuro");
        }
    }

    private void render(View root, String name) throws Exception {
        int width = Ui.dp(root.getContext(), root.getResources().getConfiguration().screenWidthDp);
        int height = Ui.dp(root.getContext(), root.getResources().getConfiguration().screenHeightDp);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        Thread.sleep(300);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        root.draw(new Canvas(bitmap));
        File directory = new File("build/qa");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create QA directory");
        }
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    @Test public void renderMultipleActivationPlacesForVisualReview() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id)
                .putExtra(StationEditorActivity.EXTRA_EDIT_ARM, true);
        try (ActivityController<StationEditorActivity> controller =
                     Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            render(controller.get().findViewById(android.R.id.content), "activation-places");
        }
    }

    @Test public void renderNearbyStationPickerForVisualReview() throws Exception {
        StationRepository.upsert(RuntimeEnvironment.getApplication(), StationRepository.IKEBUKURO);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id)
                .putExtra(StationEditorActivity.EXTRA_EDIT_ARM, true);
        try (ActivityController<StationEditorActivity> controller =
                     Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            View background = controller.get().findViewById(android.R.id.content);
            render(background, "activation-places");
            ReflectionHelpers.callInstanceMethod(controller.get(), "chooseActivationStation");
            StationPickerDialog picker = ReflectionHelpers.getField(controller.get(), "placePicker");
            View modal = picker.getWindow().getDecorView();
            android.widget.ListView list = (android.widget.ListView)findStation(modal, "駅の候補");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (list.getAdapter().getCount() == 0 && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                Thread.sleep(10);
            }
            org.junit.Assert.assertTrue(list.getAdapter().getCount() > 0);
            int width = Ui.dp(controller.get(), 412), height = Ui.dp(controller.get(), 892);
            int sheetHeight = Ui.dp(controller.get(), DesignTokens.Size.SHEET_MAX);
            modal.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sheetHeight, View.MeasureSpec.EXACTLY));
            modal.layout(0, 0, width, sheetHeight);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            background.draw(canvas);
            android.graphics.Paint dim = new android.graphics.Paint();
            dim.setColor(android.graphics.Color.argb(90, 0, 0, 0));
            canvas.drawRect(0, 0, width, height, dim);
            canvas.translate(0, height - sheetHeight);
            modal.draw(canvas);
            try (FileOutputStream output = new FileOutputStream(new File("build/qa/station-candidates.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();
        }
    }

    @Test public void renderNewDefaultsForVisualReview() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        allowMonitoring(context);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            render(controller.get().findViewById(android.R.id.content), "default-home");
            assertStationVisible(controller.get().findViewById(android.R.id.content), "池袋駅");
        }
        Intent intent = new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id)
                .putExtra(StationEditorActivity.EXTRA_EDIT_ARM, true);
        try (ActivityController<StationEditorActivity> controller = Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            render(controller.get().findViewById(android.R.id.content), "default-on-areas");
        }
    }

    @Test @Config(qualifiers = "w840dp-h740dp-xhdpi")
    public void renderUnfoldedEditorForVisualReview() throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id);
        try (ActivityController<StationEditorActivity> controller = Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            render(root, "editor-unfolded");
            StationMapView map = ReflectionHelpers.getField(controller.get(), "map");
            View save = ReflectionHelpers.getField(controller.get(), "saveButton");
            assertStationVisibleWithin(root, save);
            org.junit.Assert.assertTrue(map.getWidth() > Ui.dp(controller.get(), 400));
        }
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xhdpi")
    public void renderWithLargerTextForVisualReview() throws Exception {
        RuntimeEnvironment.setFontScale(1.3f);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.IKEBUKURO.id);
        try (ActivityController<StationEditorActivity> controller = Robolectric.buildActivity(StationEditorActivity.class, intent).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            render(root, "editor-large-text");
            assertStationVisibleWithin(root, ReflectionHelpers.getField(controller.get(), "saveButton"));
            for(String field:new String[]{"notificationTab","armTab"}) {
                android.widget.Button tab=ReflectionHelpers.getField(controller.get(),field);
                org.junit.Assert.assertTrue("Tab label must fit without clipping",tab.getLayout().getHeight()
                        <=tab.getHeight()-tab.getCompoundPaddingTop()-tab.getCompoundPaddingBottom());
            }
            StationMapView map = ReflectionHelpers.getField(controller.get(), "map");
            org.junit.Assert.assertTrue(map.getHeight() > Ui.dp(controller.get(), 240));
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }

    @Test public void renderAlarmForVisualReview() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        allowMonitoring(context);
        MonitorStateStore.setActive(context, MonitorStateStore.MODE_ALERTING, StationRepository.IKEBUKURO);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            render(root, "home-alarm");
            org.junit.Assert.assertNotNull(findStation(root, "池袋駅の振動を止める"));
        }
    }

    private void assertStationVisibleWithin(View root, View view) {
        int[] position = new int[2]; view.getLocationOnScreen(position);
        org.junit.Assert.assertTrue(view.getHeight() >= Ui.dp(view.getContext(), 48));
        org.junit.Assert.assertTrue(position[1] >= 0 && position[1] + view.getHeight() <= root.getHeight());
    }
    private void allowMonitoring(Context context) {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        org.robolectric.Shadows.shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        org.robolectric.Shadows.shadowOf(context.getSystemService(PowerManager.class))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        ReflectionHelpers.setStaticField(GeofenceRegistrar.class, "registrationState", GeofenceRegistrar.STATE_REGISTERED);
        MonitorStateStore.migrateForReliableArming(context);
    }
}
