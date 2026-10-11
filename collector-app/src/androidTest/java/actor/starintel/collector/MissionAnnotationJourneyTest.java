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

/** Synthetic mission UI only: does not start sensors, microphone, or a collection service. */
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
                input.requestRectangleOnScreen(new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight()), true);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "synthetic-open-interval.png");
            scenario.onActivity(activity -> findText(activity.getWindow().getDecorView(), "END INTERVAL").performClick());
            try (StarWirelessStore store = new StarWirelessStore(context)) {
                assertNull(store.activeAnnotation("synthetic-annotation-test"));
                assertEquals(3, store.recentAnnotations("synthetic-annotation-test").size());
                assertEquals("Synthetic note: north entrance", store.recentAnnotations("synthetic-annotation-test").get(2).text);
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            capture(context, "synthetic-ended-interval.png");
        } finally {
            context.getSharedPreferences(CollectorService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
            context.deleteDatabase("star-wireless.db");
        }
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

    private static void capture(Context context, String name) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "mission-annotations-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }
}
