package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class EditorFlowTest {
    private Context context;

    @Before
    public void clearSettings() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        PlaceFixtures.install(context);
    }

    private ActivityController<StationEditorActivity> openEditor() {
        Intent intent = new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id);
        return Robolectric.buildActivity(StationEditorActivity.class, intent).setup();
    }

    @Test
    public void defaultEditorOnlyShowsOneCircle() {
        try (ActivityController<StationEditorActivity> controller = openEditor()) {
            StationEditorActivity activity = controller.get();
            View root = activity.findViewById(android.R.id.content);
            assertFalse(hasText(root, "次の通知"));
            assertFalse(hasText(root, "見守り"));
            assertFalse(hasText(root, "詳細"));
            StationMapView map = ReflectionHelpers.getField(activity, "map");
            assertEquals(RadiusSettings.Ring.ALERT, map.getFocusedRing());
        }
    }

    @Test
    public void savingPersistsRadiusAndKeepsStationIdentity() throws Exception {
        try (ActivityController<StationEditorActivity> controller = openEditor()) {
            StationEditorActivity activity = controller.get();
            StationMapView map = ReflectionHelpers.getField(activity, "map");
            map.setFocusedRadius(350.0);
            ReflectionHelpers.callInstanceMethod(activity, "saveStation");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!activity.isFinishing() && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertTrue("Save should finish the editor", activity.isFinishing());
            Station saved = StationRepository.findById(context, StationRepository.ASAKADAI.id);
            assertEquals(350.0, saved.alertRadiusMeters, 0.0);
            assertEquals(1_350.0, saved.rearmRadiusMeters, 0.0);
            assertEquals(5_000.0, saved.monitorRadiusMeters, 0.0);
            assertEquals("朝霞台駅", saved.name);
        }
    }

    @Test
    public void editingAndCancellingDoesNotModifySavedSettings() {
        try (ActivityController<StationEditorActivity> controller = openEditor()) {
            StationEditorActivity activity = controller.get();
            StationMapView map = ReflectionHelpers.getField(activity, "map");
            map.setFocusedRadius(350.0);
            activity.onBackPressed();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.isFinishing());
            assertEquals(500.0, StationRepository.findById(context,
                    StationRepository.ASAKADAI.id).alertRadiusMeters, 0.0);
        }
    }

    @Test
    public void legacySettingsKeepTheirNotificationRadiusAndAdoptAutomaticBoundaries() throws Exception {
        Station original = StationRepository.ASAKADAI;
        org.json.JSONObject old = new org.json.JSONObject()
                .put("id", original.id).put("name", original.name)
                .put("latitude", original.latitude).put("longitude", original.longitude)
                .put("alertRadiusMeters", 587.0).put("rearmRadiusMeters", 1_537.0)
                .put("monitorRadiusMeters", 5_157.0).put("enabled", false);
        context.getSharedPreferences("station_rules_v1", 0).edit()
                .putString("stations_json", new org.json.JSONArray().put(old).toString()).commit();
        try (ActivityController<StationEditorActivity> controller = openEditor()) {
            StationMapView map = ReflectionHelpers.getField(controller.get(), "map");
            assertEquals(587.0, map.getRadii().alertMeters, 0.0);
            assertEquals(1_587.0, map.getRadii().rearmMeters, 0.0);
            assertEquals(5_000.0, map.getRadii().monitorMeters, 0.0);
            assertFalse(StationRepository.findById(context, original.id).enabled);
            controller.get().onBackPressed();
            assertTrue("Unchanged notification circle should close without a discard prompt",
                    controller.get().isFinishing());
        }
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    @Test
    public void rotationKeepsUnsavedEdits() {
        ActivityController<StationEditorActivity> first = openEditor();
        StationMapView firstMap = ReflectionHelpers.getField(first.get(), "map");
        firstMap.setFocusedRadius(300.0);
        Bundle state = new Bundle();
        first.saveInstanceState(state).pause().stop().destroy();
        Intent intent = new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id);
        try (ActivityController<StationEditorActivity> second =
                     Robolectric.buildActivity(StationEditorActivity.class, intent)
                             .create(state).start().resume().visible()) {
            StationMapView secondMap = ReflectionHelpers.getField(second.get(), "map");
            assertEquals(300.0, secondMap.getRadii().alertMeters, 0.0);
        }
    }
}
