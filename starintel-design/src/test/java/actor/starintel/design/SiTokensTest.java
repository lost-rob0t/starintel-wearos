package actor.starintel.design;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SiTokensTest {
    @Test
    public void electricPaletteIsLockedToRealQtileValues() {
        assertEquals(0xFF202146, SiTokens.ELECTRIC.background);
        assertEquals(0xFF170C32, SiTokens.ELECTRIC.surface);
        assertEquals(0xFF2DE2E6, SiTokens.ELECTRIC.accent);
        assertEquals(0xFFF6019D, SiTokens.ELECTRIC.accentAlt);
        assertEquals(0xFF62FF00, SiTokens.ELECTRIC.ok);
        assertEquals(0xFFFBA922, SiTokens.ELECTRIC.warn);
        assertEquals(0xFFDD546E, SiTokens.ELECTRIC.danger);
        assertEquals(0xFFF3F4F5, SiTokens.ELECTRIC.text);
    }

    @Test
    public void everyPaletteProvidesEverySemanticPosition() {
        for (SiTokens.Palette palette : SiTokens.palettes()) {
            assertTrue(palette.id, SiTokens.contrastRatio(palette.text, palette.background) >= 7.0d);
            assertTrue(palette.id, SiTokens.contrastRatio(palette.muted, palette.background) >= 4.5d);
            assertTrue(palette.id, SiTokens.contrastRatio(palette.muted, palette.surface) >= 4.5d);
            assertNotEquals(palette.accent, palette.background);
        }
    }

    @Test
    public void accentFilledControlsStayLegible() {
        // onColor() picks background-on-accent when it clears 4.5:1; lock that guarantee.
        for (SiTokens.Palette palette : SiTokens.palettes()) {
            assertTrue(palette.id + " accent control legibility",
                    SiTokens.contrastRatio(palette.background, palette.accent) >= 4.5d);
            assertTrue(palette.id + " alt accent visibility",
                    SiTokens.contrastRatio(palette.background, palette.accentAlt) >= 2.0d);
        }
    }

    @Test
    public void paletteIdsAreStableAndResolve() {
        assertEquals(SiTokens.CYAN, SiTokens.byId("cyan"));
        assertEquals(SiTokens.CYAN, SiTokens.byId("unknown"));
        assertEquals(SiTokens.ELECTRIC, SiTokens.byId("electric"));
        assertEquals(5, SiTokens.palettes().length);
    }

    @Test
    public void contrastMathMatchesKnownRatios() {
        assertEquals(21.0d, SiTokens.contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01d);
        assertEquals(1.0d, SiTokens.contrastRatio(0xFF808080, 0xFF808080), 0.01d);
    }
}
