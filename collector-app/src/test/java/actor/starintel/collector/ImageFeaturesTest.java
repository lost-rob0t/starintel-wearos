package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImageFeaturesTest {
    @Test
    public void flatImageHasNoEdgesAndKnownLuma() {
        int[] pixels = new int[64 * 64];
        java.util.Arrays.fill(pixels, 0xFF808080);
        ImageFeatures.Stats stats = ImageFeatures.analyze(pixels, 64, 64);
        assertEquals(0.503d, stats.meanLuma, 0.01d);
        assertEquals(0.0d, stats.lumaStdDev, 0.0001d);
        assertEquals(0.0d, stats.edgeDensity, 0.0001d);
    }

    @Test
    public void checkerboardHasHighEdgeDensityAndVariance() {
        int[] pixels = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                pixels[y * 64 + x] = ((x + y) % 2 == 0) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        ImageFeatures.Stats stats = ImageFeatures.analyze(pixels, 64, 64);
        assertTrue(stats.lumaStdDev > 0.4d);
        assertTrue(stats.edgeDensity > 0.5d);
    }

    @Test
    public void laplacianVarianceIsZeroForFlat() {
        int[] pixels = new int[16 * 16];
        java.util.Arrays.fill(pixels, 0xFF101010);
        assertEquals(0.0d, ImageFeatures.laplacianVariance(pixels, 16, 16), 0.0001d);
    }

    @Test
    public void rejectsBadBuffers() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageFeatures.analyze(new int[10], 64, 64));
        assertThrows(IllegalArgumentException.class,
                () -> ImageFeatures.analyze(null, 1, 1));
    }
}
