package actor.starintel.wear.complications
import actor.starintel.wear.data.*
import android.graphics.Color
import android.widget.Button
import actor.starintel.wear.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityGraphRendererTest {
    private val now get() = System.currentTimeMillis() / 1000
    @Test fun everyRangeRendersActiveAndAmbientImages() {
        ActivityRange.entries.forEach { range ->
            val image = ActivityGraphRenderer.render(listOf(ActivityPoint(now, 12)),range)
            assertEquals(274,image.active.width); assertEquals(66,image.active.height)
            assertEquals(image.active.width,image.ambient.width)
            assertTrue((0 until image.active.width).any { x -> (12 until 60).any { y -> Color.alpha(image.active.getPixel(x,y)) > 0 } })
        }
    }
    @Test fun emptyDataDoesNotPaintFakeTrend() {
        val image=ActivityGraphRenderer.render(emptyList(),ActivityRange.H1).active
        assertTrue((12 until 42).all { y -> (0 until image.width).all { x -> Color.alpha(image.getPixel(x,y)) == 0 } })
    }
    @Test fun realZeroIsVisibleAtBaseline() {
        val image=ActivityGraphRenderer.render(listOf(ActivityPoint(now-300,0),ActivityPoint(now,0)),ActivityRange.H1).active
        assertTrue((250 until 268).any { x -> Color.alpha(image.getPixel(x,59)) > 0 })
    }
    @Test fun gapDoesNotDrawConnectingSegment() {
        val image=ActivityGraphRenderer.render(listOf(ActivityPoint(now-3600,5),ActivityPoint(now-1800,null,gap=true),ActivityPoint(now,5)),ActivityRange.H1).active
        assertTrue(Color.green(image.getPixel(137,13)) < 150)
    }
    @Test fun terminalButtonsKeepAccessibleTouchTargetAndDisabledState() {
        val button=Button(RuntimeEnvironment.getApplication())
        button.applyStarIntelTheme(StarIntelThemeStore.palette(StarIntelThemeId.CYAN))
        assertTrue(button.minHeight >= (48 * button.resources.displayMetrics.density).toInt())
        assertTrue(button.textColors.isStateful)
        button.isEnabled=false
        assertNotEquals(StarIntelThemeStore.palette(StarIntelThemeId.CYAN).accent,button.currentTextColor)
    }
}
