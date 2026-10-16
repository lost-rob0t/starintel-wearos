package actor.starintel.quasar

import actor.starintel.design.SiTokens
import org.junit.Assert.*
import org.junit.Test

class QuasarAppearanceTest {
    @Test fun builtInThemesKeepForegroundsReadableOnEverySurface() {
        for (dark in listOf(false, true)) for (palette in listOf(QuasarPalette.starintel(dark), QuasarPalette.synthwave(dark))) {
            for (surface in listOf(palette.background, palette.surface, palette.raised)) {
                for (foreground in listOf(palette.text, palette.muted, palette.accent, palette.alternate, palette.good, palette.warning, palette.danger)) {
                    assertTrue("Foreground must meet 4.5:1", SiTokens.contrastRatio(foreground, surface) >= 4.5)
                }
            }
            assertTrue(SiTokens.contrastRatio(palette.accent, palette.onAccent) >= 4.5)
        }
        assertEquals(SiTokens.CYAN.background, QuasarPalette.starintel(true).background)
        assertEquals(SiTokens.CYAN.accent, QuasarPalette.starintel(true).accent)
        assertNotEquals(QuasarPalette.starintel(true).accent, QuasarPalette.synthwave(true).accent)
    }

    @Test fun customThemeRoundTripsBothVariantsWithoutLosingUserColors() {
        val source = QuasarAppearance.defaultCustom()
        source.put("name", "Field kit")
        source.getJSONObject("dark").put("accent", "#FFD477")
        val imported = QuasarAppearance.validateCustom(source.toString())
        assertEquals("Field kit", imported.getString("name"))
        assertEquals(QuasarPalette.hex("#FFD477"), QuasarAppearance.customPalette(imported, true).accent)
        assertNotEquals(QuasarAppearance.customPalette(imported, true).background, QuasarAppearance.customPalette(imported, false).background)
    }

    @Test fun invalidCustomColorsIdentifyTheVariantAndInput() {
        val source = QuasarAppearance.defaultCustom()
        source.getJSONObject("light").put("text", "#FFFFFF")
        val failure = runCatching { QuasarAppearance.validateCustom(source.toString()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("light.text:"))
        source.getJSONObject("light").put("text", "#122139").put("background", "#101010")
        assertTrue(runCatching { QuasarAppearance.validateCustom(source.toString()) }.exceptionOrNull()?.message.orEmpty().startsWith("light.background:"))
        source.getJSONObject("dark").put("accent", "cyan")
        assertTrue(runCatching { QuasarAppearance.customPalette(source, true) }.exceptionOrNull()?.message.orEmpty().startsWith("dark.accent:"))
    }

    @Test fun importRejectsUnboundedOrIncompleteDocuments() {
        assertTrue(runCatching { QuasarAppearance.validateCustom(" ".repeat(8193)) }.isFailure)
        assertTrue(runCatching { QuasarAppearance.validateCustom("{}") }.isFailure)
        val source = QuasarAppearance.defaultCustom().apply { remove("light") }
        assertTrue(runCatching { QuasarAppearance.validateCustom(source.toString()) }.isFailure)
    }
}
