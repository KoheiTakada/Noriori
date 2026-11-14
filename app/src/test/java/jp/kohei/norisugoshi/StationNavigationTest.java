package jp.kohei.norisugoshi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

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
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class StationNavigationTest {
    private Context context;

    @Before
    public void clearSettings() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        GeofenceRegistrar.resetRegistrationState();
        PlaceFixtures.install(context);
    }

    @Test
    public void openingIkebukuroFromListPreservesAsakadaiCustomSettings() {
        Station asakadai = StationRepository.ASAKADAI;
        StationRepository.upsert(context, new Station(asakadai.id, asakadai.name,
                asakadai.latitude, asakadai.longitude, 350, 1_537, 5_157, false));

        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(2, StationRepository.getAll(context).size());
            Station preserved = StationRepository.findById(context, asakadai.id);
            assertEquals(350, preserved.alertRadiusMeters, 0);
            assertEquals(1_350, preserved.rearmRadiusMeters, 0);
            assertEquals(5_000, preserved.monitorRadiusMeters, 0);
            assertTrue(preserved.enabled);
            click(controller.get(), "池袋駅の通知範囲を編集");
            Intent editor = org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(StationRepository.IKEBUKURO.id,
                    editor.getStringExtra(StationEditorActivity.EXTRA_STATION_ID));
        }
    }

    @Test
    public void deletingIkebukuroKeepsItDeletedOnReopen() {
        StationRepository.delete(context, StationRepository.IKEBUKURO.id);
        try (ActivityController<MainActivity> ignored = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(1, StationRepository.getAll(context).size());
        }
    }

    @Test
    public void headerAddOpensNewStationWithoutReplacingExistingStations() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(controller.get(), "駅を追加");
            Intent editor = org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull(editor);
            assertFalse(editor.hasExtra(StationEditorActivity.EXTRA_STATION_ID));
            assertEquals(2, StationRepository.getAll(context).size());
        }
    }

    @Test
    public void switchingAndSavingIkebukuroDoesNotOverwriteAsakadai() throws Exception {
        Intent start = new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id);
        try (ActivityController<StationEditorActivity> first =
                     Robolectric.buildActivity(StationEditorActivity.class, start).setup()) {
            click(first.get(), "駅を切り替える");
            PopupMenu picker = ShadowPopupMenu.getLatestPopupMenu();
            assertTrue(picker.getMenu().performIdentifierAction(1, 0));
            Intent next = org.robolectric.Shadows.shadowOf(first.get()).getNextStartedActivity();
            assertEquals(StationRepository.IKEBUKURO.id,
                    next.getStringExtra(StationEditorActivity.EXTRA_STATION_ID));
            try (ActivityController<StationEditorActivity> second =
                         Robolectric.buildActivity(StationEditorActivity.class, next).setup()) {
                StationMapView map = ReflectionHelpers.getField(second.get(), "map");
                assertEquals(StationRepository.IKEBUKURO.latitude,
                        (double) ReflectionHelpers.getField(map, "latitude"), 0);
                map.setFocusedRadius(250);
                ReflectionHelpers.callInstanceMethod(second.get(), "saveStation");
                awaitSave(second.get());
            }
            assertEquals(250, StationRepository.findById(context,
                    StationRepository.IKEBUKURO.id).alertRadiusMeters, 0);
            assertEquals(500, StationRepository.findById(context,
                    StationRepository.ASAKADAI.id).alertRadiusMeters, 0);
            assertEquals(2, StationRepository.getAll(context).size());
        }
    }

    @Test
    public void unsavedEditsCanBeKeptWhenSwitchingStation() {
        Intent start = new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, StationRepository.ASAKADAI.id);
        try (ActivityController<StationEditorActivity> controller =
                     Robolectric.buildActivity(StationEditorActivity.class, start).setup()) {
            StationMapView map = ReflectionHelpers.getField(controller.get(), "map");
            map.setFocusedRadius(350);
            click(controller.get(), "駅を切り替える");
            ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(1, 0);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            assertFalse(controller.get().isFinishing());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(controller.get().isFinishing());
            assertEquals(350, map.getFocusedRadius(), 0);
            assertEquals(500, StationRepository.findById(context,
                    StationRepository.ASAKADAI.id).alertRadiusMeters, 0);
        }
    }

    private static void awaitSave(Activity activity) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!activity.isFinishing() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("Save should finish the editor", activity.isFinishing());
    }

    private static void click(Activity activity, String description) {
        View target = find(activity.findViewById(android.R.id.content), description);
        assertNotNull(description, target);
        assertTrue(target.performClick());
    }

    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
