package actor.starintel.collector;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;

/** Isolated emulator fixtures: synthetic notes plus graceful collection service stop, without microphone. */
public class MissionAnnotationJourneyTest {
    @Test public void recordsNotesAndRestoresIntervalAndDraftAfterRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("star-wireless.db");
        context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE).edit().clear()
                .putString(CollectorService.KEY_RUN_ID, "synthetic-annotation-test")
                .putBoolean(CollectorService.KEY_ACTIVE, true).commit();
        try (ActivityScenario<CollectorMissionActivity> scenario = ActivityScenario.launch(CollectorMissionActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                EditText input = findInput(root);
                input.setText("Synthetic note: north entrance");
                findText(root, "SAVE NOTE").performClick();
                assertEquals("", input.getText().toString());
                input.setText("Measured observation interval");
                findText(root, "START INTERVAL").performClick();
                assertFalse(findText(root, "START INTERVAL").isEnabled());
                assertTrue(findText(root, "END INTERVAL").isEnabled());
                input.setText("Unsaved draft survives rotation");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                EditText input = findInput(root);
                assertEquals("Unsaved draft survives rotation", input.getText().toString());
                assertFalse(findText(root, "START INTERVAL").isEnabled());
                assertTrue(findText(root, "END INTERVAL").isEnabled());
                showControls(activity);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "synthetic-open-interval.png", "Open interval: Measured observation interval", true, false, true);
            scenario.onActivity(activity -> findText(activity.getWindow().getDecorView(), "END INTERVAL").performClick());
            try (StarWirelessStore store = new StarWirelessStore(context)) {
                assertNull(store.activeAnnotation("synthetic-annotation-test"));
                assertEquals(3, store.recentAnnotations("synthetic-annotation-test").size());
                assertEquals("Synthetic note: north entrance", store.recentAnnotations("synthetic-annotation-test").get(2).text);
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "synthetic-ended-interval.png", "No interval open", true, true, false);
        } finally {
            context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
            context.deleteDatabase("star-wireless.db");
        }
    }

    @Test public void gracefulServiceStopAppendsEndWithoutStartingMicrophone() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("star-wireless.db");
        context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        android.content.Intent service = new android.content.Intent(context, CollectorService.class);
        try (ActivityScenario<CollectorMissionActivity> scenario = ActivityScenario.launch(CollectorMissionActivity.class)) {
            scenario.onActivity(activity -> activity.startForegroundService(service));
            awaitMissionState(context, true);
            String runId = context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE)
                    .getString(CollectorService.KEY_RUN_ID, "");
            assertFalse(runId.isEmpty());
            assertFalse(context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(CollectorService.KEY_AUDIO_ACTIVE, false));
            try (StarWirelessStore store = new StarWirelessStore(context)) {
                store.appendAnnotation(runId, "start", "Service lifecycle fixture", System.currentTimeMillis(), "operator");
            }
            scenario.onActivity(activity -> assertTrue(activity.stopService(service)));
            awaitMissionState(context, false);
            try (StarWirelessStore store = new StarWirelessStore(context)) {
                assertNull(store.activeAnnotation(runId));
                java.util.List<MissionAnnotation> events = store.recentAnnotations(runId);
                assertEquals(2, events.size());
                assertEquals("end", events.get(0).kind);
                assertEquals("service_stop", events.get(0).source);
                assertEquals(events.get(1).id, events.get(0).intervalId);
            }
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(findText(activity.getWindow().getDecorView(), "END INTERVAL").isEnabled());
                showControls(activity);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "service-stopped-interval.png", "Start a mission to add notes", false, false, false);
        } finally {
            context.stopService(service);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
            context.deleteDatabase("star-wireless.db");
        }
    }

    private static void awaitMissionState(Context context, boolean active) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 10000;
        while (context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(CollectorService.KEY_ACTIVE, false) != active) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) fail("Service state did not become " + active);
            Thread.sleep(50);
        }
    }

    private static void showControls(android.app.Activity activity) {
        EditText input = findInput(activity.getWindow().getDecorView());
        ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        View button = findText(activity.getWindow().getDecorView(), "END INTERVAL");
        button.requestRectangleOnScreen(new android.graphics.Rect(0, 0, button.getWidth(), button.getHeight()), true);
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasVisibleNode(android.view.accessibility.AccessibilityNodeInfo node,
            String text, boolean substring, Boolean enabled) {
        String actual = node.getText() == null ? "" : node.getText().toString();
        if (node.isVisibleToUser() && (substring ? actual.contains(text) : text.equals(actual))
                && (enabled == null || node.isEnabled() == enabled)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { if (hasVisibleNode(child, text, substring, enabled)) return true; } finally { child.recycle(); }
            }
        }
        return false;
    }

    private static boolean expectedState(android.view.accessibility.AccessibilityNodeInfo root,
            String status, boolean saveEnabled, boolean startEnabled, boolean endEnabled) {
        return root != null && "actor.starintel.collector".equals(String.valueOf(root.getPackageName()))
                && hasVisibleNode(root, status, true, null)
                && hasVisibleNode(root, "SAVE NOTE", false, saveEnabled)
                && hasVisibleNode(root, "START INTERVAL", false, startEnabled)
                && hasVisibleNode(root, "END INTERVAL", false, endEnabled);
    }

    private static void writeNode(org.xmlpull.v1.XmlSerializer xml,
            android.view.accessibility.AccessibilityNodeInfo node) throws Exception {
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "package", String.valueOf(node.getPackageName()));
        xml.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        xml.attribute(null, "bounds", bounds.toShortString());
        xml.attribute(null, "visible", Boolean.toString(node.isVisibleToUser()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { writeNode(xml, child); } finally { child.recycle(); } }
        }
        xml.endTag(null, "node");
    }

    private static void capture(Context context, String name, String status,
            boolean saveEnabled, boolean startEnabled, boolean endEnabled) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "mission-annotations-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        android.view.accessibility.AccessibilityNodeInfo root = null;
        long deadline = android.os.SystemClock.uptimeMillis() + 10000;
        // Main-thread idle is not a guarantee that recreation/IME window transactions have committed.
        // Wait for the actual foreground state, including accessibility updates after ending an interval.
        // An old tree can have visible controls while still describing the previous state.
        // Do not dismiss or accept system dialogs.
        while (true) {
            root = automation.getRootInActiveWindow();
            if (expectedState(root, status, saveEnabled, startEnabled, endEnabled)) break;
            if (android.os.SystemClock.uptimeMillis() >= deadline) break;
            if (root != null) root.recycle();
            Thread.sleep(100);
        }
        // Preserve diagnostic evidence before assertions, including a wrong/system window.
        try {
            try (FileOutputStream output = new FileOutputStream(new File(directory, name.replace(".png", ".xml")))) {
                org.xmlpull.v1.XmlSerializer xml = android.util.Xml.newSerializer();
                xml.setOutput(output, "UTF-8");
                xml.startDocument("UTF-8", true);
                if (root == null) { xml.startTag(null, "missing-window"); xml.endTag(null, "missing-window"); }
                else writeNode(xml, root);
                xml.endDocument();
            }
            Bitmap screenshot = automation.takeScreenshot();
            assertNotNull(screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
            assertNotNull("No active accessibility window", root);
            assertEquals("System dialog or another app obscures Collector", "actor.starintel.collector",
                    String.valueOf(root.getPackageName()));
            assertTrue("Annotation hierarchy did not reach expected state: " + status,
                    expectedState(root, status, saveEnabled, startEnabled, endEnabled));
        } finally { if (root != null) root.recycle(); }
    }
}
