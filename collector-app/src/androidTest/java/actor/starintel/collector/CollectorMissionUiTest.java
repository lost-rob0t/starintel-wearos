package actor.starintel.collector;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import org.junit.Test;

public final class CollectorMissionUiTest {
    @Test(timeout = 150_000L)
    public void missionSurfaceRendersCaptureGridAndManagedEdgeRuntime() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage("actor.starintel.collector");
        assertNotNull(launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.wait(Until.hasObject(By.text("Start Mission")), 20_000L));
        assertTrue(device.wait(Until.hasObject(By.descContains("Audio. Voice + transcript")), 20_000L));
        assertTrue(device.hasObject(By.descContains("Photo. Frame + features")));
        assertTrue(device.hasObject(By.descContains("Video. Media + metadata")));
        assertTrue(device.hasObject(By.descContains("WiGLE. Bulk observations")));

        UiScrollable page = new UiScrollable(new UiSelector().scrollable(true));
        page.setAsVerticalList();
        long deadline = android.os.SystemClock.uptimeMillis() + 120_000L;
        boolean ready = false;
        while (android.os.SystemClock.uptimeMillis() < deadline && !ready) {
            ready = device.hasObject(By.textContains("ECL + SENTO"));
            if (!ready) {
                ready = page.scrollIntoView(new UiSelector().textContains("ECL + SENTO"));
                if (!ready) android.os.SystemClock.sleep(500L);
            }
        }
        assertTrue("Managed StarIntel Edge runtime did not become ready", ready);
    }
}
