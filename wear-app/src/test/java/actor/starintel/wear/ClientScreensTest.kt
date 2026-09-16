package actor.starintel.wear

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import actor.starintel.wear.data.RecentDocument
import actor.starintel.wear.data.RecentDocuments
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w227dp-h227dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClientScreensTest {
    private fun checkScreen(type: Class<out Activity>) {
        val controller=Robolectric.buildActivity(type).setup().visible()
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val activity=controller.get()
            val content=activity.findViewById<View>(android.R.id.content)
            assertNotNull(content)
            content.measure(View.MeasureSpec.makeMeasureSpec(227,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(227,View.MeasureSpec.EXACTLY))
            content.layout(0,0,227,227)
            val bitmap=Bitmap.createBitmap(227,227,Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            val file=File("build/reports/client-screens/${type.simpleName}.png")
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { controller.pause().stop().destroy() }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun radarRendersArrivalsRetainsStaleDataAndOpensExactDocument() {
        val controller = Robolectric.buildActivity(RadarActivity::class.java).create().start()
        try {
            val activity = controller.get()
            val doc = RecentDocument("doc-a/encoded", "Recent report", "document", "public-corpus", 1_800_000_000)
            activity.renderFeed(RecentDocuments(listOf(doc), partial=true))
            val content = activity.findViewById<View>(android.R.id.content)
            fun texts() = descendants(content).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts().any { it.contains("1 arrivals") })
            activity.renderFeed(RecentDocuments(error="Network unavailable"))
            assertTrue(texts().any { it.startsWith("Stale") })
            val button = descendants(content).filterIsInstance<Button>().single()
            assertTrue(button.text.toString().contains("Recent report"))
            button.performClick()
            assertEquals(doc.id, Shadows.shadowOf(activity).nextStartedActivity.getStringExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID))
            activity.renderFeed(RecentDocuments())
            assertTrue(texts().contains("No recent documents"))
            assertTrue(descendants(content).filterIsInstance<Button>().isEmpty())
        } finally { controller.stop().destroy() }
    }

    @Test fun dashboardLaunchAndDispose()=checkScreen(MainActivity::class.java)
    @Test fun radarLaunchAndDispose()=checkScreen(RadarActivity::class.java)
    @Test fun trendLaunchAndDispose()=checkScreen(ActivityTrendActivity::class.java)
    @Test fun graphLaunchAndDispose()=checkScreen(GraphActivity::class.java)
    @Test fun searchLaunchAndDispose()=checkScreen(SearchActivity::class.java)
    @Test fun explorerLaunchAndDispose()=checkScreen(ExplorerActivity::class.java)
    @Test fun targetsLaunchAndDispose()=checkScreen(TargetsActivity::class.java)
    @Test fun gaugesLaunchAndDispose()=checkScreen(GoalGaugesActivity::class.java)
    @Test fun settingsLaunchAndDispose()=checkScreen(ConfigActivity::class.java)
}
