package actor.starintel.collector;

/**
 * Deterministic image statistics computed from ARGB pixels.
 *
 * Pure Java so unit tests can verify behavior on synthetic patterns; the OpenCV
 * wrapper mirrors these definitions when the native library is available.
 */
public final class ImageFeatures {
    public static final int MAX_PIXELS = 4_194_304;

    /** Bounded, comparable feature vector for a captured frame. */
    public static final class Stats {
        public final double meanLuma;
        public final double lumaStdDev;
        public final double sharpness;
        public final double edgeDensity;
        public final int dominantRgb;
        public final int width;
        public final int height;

        Stats(double meanLuma, double lumaStdDev, double sharpness, double edgeDensity,
                int dominantRgb, int width, int height) {
            this.meanLuma = meanLuma;
            this.lumaStdDev = lumaStdDev;
            this.sharpness = sharpness;
            this.edgeDensity = edgeDensity;
            this.dominantRgb = dominantRgb;
            this.width = width;
            this.height = height;
        }
    }

    private ImageFeatures() {}

    public static Stats analyze(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("pixel buffer out of range");
        }
        double sum = 0.0d;
        double sumSquares = 0.0d;
        int[] histogram = new int[4_096];
        double sharpnessAccumulator = 0.0d;
        long edges = 0L;
        long edgeSamples = 0L;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                double luma = luma(pixel);
                sum += luma;
                sumSquares += luma * luma;
                histogram[quantize(pixel)]++;

                int right = x + 1 < width ? argb[y * width + x + 1] : pixel;
                int down = y + 1 < height ? argb[(y + 1) * width + x] : pixel;
                double gx = luma(right) - luma;
                double gy = luma(down) - luma;
                double gradient = Math.sqrt(gx * gx + gy * gy);
                if (x + 1 < width || y + 1 < height) {
                    sharpnessAccumulator += gradient * gradient;
                    edgeSamples++;
                    if (gradient > 0.18d) edges++;
                }
            }
        }

        int count = width * height;
        double mean = sum / count;
        double variance = Math.max(0.0d, sumSquares / count - mean * mean);

        int dominant = 0;
        int dominantCount = -1;
        for (int bucket = 0; bucket < histogram.length; bucket++) {
            if (histogram[bucket] > dominantCount) {
                dominantCount = histogram[bucket];
                dominant = bucket;
            }
        }

        return new Stats(
                mean,
                Math.sqrt(variance),
                edgeSamples == 0 ? 0.0d : sharpnessAccumulator / edgeSamples,
                edgeSamples == 0 ? 0.0d : (double) edges / edgeSamples,
                bucketCenter(dominant),
                width,
                height);
    }

    /** Gray-scale Laplacian variance (OpenCV-style sharpness metric) on a downsampled grid. */
    public static double laplacianVariance(int[] argb, int width, int height) {
        if (argb == null || width < 3 || height < 3 || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("pixel buffer out of range");
        }
        double sum = 0.0d;
        double sumSquares = 0.0d;
        long samples = 0L;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int base = y * width + x;
                double center = luma(argb[base]);
                double laplacian = luma(argb[base - 1]) + luma(argb[base + 1])
                        + luma(argb[base - width]) + luma(argb[base + width])
                        - 4.0d * center;
                sum += laplacian;
                sumSquares += laplacian * laplacian;
                samples++;
            }
        }
        if (samples == 0L) return 0.0d;
        double mean = sum / samples;
        return Math.max(0.0d, sumSquares / samples - mean * mean);
    }

    static double luma(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126d * r + 0.7152d * g + 0.0722d * b) / 255.0d;
    }

    private static int quantize(int argb) {
        int r = ((argb >>> 16) & 0xFF) >>> 4;
        int g = ((argb >>> 8) & 0xFF) >>> 4;
        int b = (argb & 0xFF) >>> 4;
        return (r << 8) | (g << 4) | b;
    }

    private static int bucketCenter(int bucket) {
        int r = ((bucket >> 8) & 0xF) << 4;
        int g = ((bucket >> 4) & 0xF) << 4;
        int b = (bucket & 0xF) << 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
