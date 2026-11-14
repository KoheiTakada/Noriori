package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class StationAdditionTest {
    private Context context;
    private Station original;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("station_rules_v1", 0).edit().clear().commit();
        context.getSharedPreferences("monitor_state", 0).edit().clear().commit();
        PlaceFixtures.install(context);
        original = StationRepository.findById(context, StationRepository.ASAKADAI.id);
    }
    private ActivityController<StationEditorActivity> open() {
        return Robolectric.buildActivity(StationEditorActivity.class, new Intent(context, StationEditorActivity.class)
                .putExtra(StationEditorActivity.EXTRA_STATION_ID, original.id)
                .putExtra(StationEditorActivity.EXTRA_EDIT_ARM, true)).setup();
    }
    @Test public void choosingAnAddedStationPreservesEveryExistingAreaAndDestination() throws Exception {
        try (ActivityController<StationEditorActivity> controller = open()) {
            StationEditorActivity activity = controller.get();
            View add = find(activity.findViewById(android.R.id.content), "オンにする場所を追加");
            assertNotNull(add); assertTrue(add.performClick());
            StationPickerDialog picker = ReflectionHelpers.getField(activity, "placePicker");
            EditText query = ReflectionHelpers.getField(picker, "query");
            query.setText("北習志野");
            ListView list = (ListView)find(picker.getWindow().getDecorView(), "駅の候補");
            awaitResults(list, "北習志野駅");
            list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
            assertFalse(picker.isShowing());
            ReflectionHelpers.callInstanceMethod(activity, "saveStation");
            awaitSave(activity);
            Station saved = StationRepository.findById(context, original.id);
            assertEquals(original.name, saved.name);
            assertEquals(original.latitude, saved.latitude, 0);
            assertEquals(original.longitude, saved.longitude, 0);
            assertEquals(3, saved.activationAreas.size());
            assertEquals(original.activationAreas.get(0).id, saved.activationAreas.get(0).id);
            assertEquals(original.activationAreas.get(1).id, saved.activationAreas.get(1).id);
            assertEquals("北習志野駅", saved.activationAreas.get(2).name);
        }
    }
    @Test public void cancelingAddDoesNotChangeTheSelectedPlaceOrDraft() {
        try (ActivityController<StationEditorActivity> controller = open()) {
            StationEditorActivity activity = controller.get();
            ReflectionHelpers.callInstanceMethod(activity, "chooseActivationStation");
            StationPickerDialog picker = ReflectionHelpers.getField(activity, "placePicker");
            ((EditText)ReflectionHelpers.getField(picker, "query")).setText("北習");
            picker.dismiss();
            List<ActivationArea> areas = ReflectionHelpers.getField(activity, "areas");
            assertEquals(2, areas.size());
            assertEquals(original.activationAreas.get(0).id, areas.get(0).id);
            assertEquals(0, (int)ReflectionHelpers.getField(activity, "selectedArea"));
            assertFalse((boolean)ReflectionHelpers.callInstanceMethod(activity, "hasChanges"));
        }
    }
    @Test public void searchingForNotificationStationCannotReplaceAnExistingDestination() {
        try (ActivityController<StationEditorActivity> controller = open()) {
            ReflectionHelpers.callInstanceMethod(controller.get(), "chooseNotificationStation");
            assertNull(ReflectionHelpers.getField(controller.get(), "placePicker"));
            assertEquals(original.name, ReflectionHelpers.getField(controller.get(), "resolvedName"));
        }
    }
    @Test public void mapLongPressAlwaysAddsInsteadOfReplacingTheSelectedArea() {
        try (ActivityController<StationEditorActivity> controller = open()) {
            ReflectionHelpers.callInstanceMethod(controller.get(), "setArmPlace",
                    ReflectionHelpers.ClassParameter.from(String.class, "別の場所"),
                    ReflectionHelpers.ClassParameter.from(double.class, original.latitude + 0.05),
                    ReflectionHelpers.ClassParameter.from(double.class, original.longitude));
            List<ActivationArea> areas = ReflectionHelpers.getField(controller.get(), "areas");
            assertEquals(3, areas.size());
            assertEquals(original.activationAreas.get(0).id, areas.get(0).id);
            assertEquals(original.activationAreas.get(1).id, areas.get(1).id);
        }
    }
    private void awaitResults(ListView list, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS);
            if (list.getAdapter().getCount() > 0 && ((StationCatalog.Entry)list.getAdapter().getItem(0)).name.equals(name)) return;
            Thread.sleep(10);
        }
        fail("Candidate should appear without a network request");
    }
    private void awaitSave(StationEditorActivity activity) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!activity.isFinishing() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue(activity.isFinishing());
    }
    private View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), description);
                if (result != null) return result;
            }
        }
        return null;
    }
}
