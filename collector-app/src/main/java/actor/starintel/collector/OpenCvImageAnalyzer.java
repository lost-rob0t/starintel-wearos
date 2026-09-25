package actor.starintel.collector;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Decodes captured photos and computes the bounded feature vector used in image documents.
 *
 * Uses OpenCV (blur + Laplacian variance + Canny edge density) when the native library
 * loads, and always mirrors the result through the pure-Java ImageFeatures definitions
 * so the two implementations agree within documented tolerances.
 */
public final class OpenCvImageAnalyzer {
    static final int MAX_DECODE_DIMENSION = 1_280;
    static final long MAX_IMAGE_BYTES = 16L * 1_024 * 1_024;

    private OpenCvImageAnalyzer() {}

    public static boolean openCvAvailable() {
        return OpenCVLoader.initLocal();
    }

    /** Normalizes EXIF rotation and downsamples into app-private storage; returns the stats. */
    public static ImageFeatures.Stats normalizeAndAnalyze(File input, File normalized) throws IOException {
        if (input.length() > MAX_IMAGE_BYTES) throw new IOException("Photo exceeds capture cap");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Photo unreadable");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap decoded = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("Photo decode failed");
        Bitmap rotated = applyExifRotation(input, decoded);

        ImageFeatures.Stats stats = analyze(rotated);

        File parent = normalized.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create image directory");
        }
        try (FileOutputStream out = new FileOutputStream(normalized)) {
            if (!rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                throw new IOException("Photo normalization failed");
            }
        }
        if (rotated != decoded) decoded.recycle();
        rotated.recycle();
        return stats;
    }

    public static ImageFeatures.Stats analyze(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        ImageFeatures.Stats pure = ImageFeatures.analyze(pixels, width, height);
        if (!openCvAvailable()) return pure;

        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        try {
            Utils.bitmapToMat(bitmap, mat);
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY);

            Mat blurred = new Mat();
            Imgproc.GaussianBlur(mat, blurred, new Size(3, 3), 0.0d);
            Mat laplacian = new Mat();
            Imgproc.Laplacian(blurred, laplacian, CvType.CV_64F);
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble stdDev = new MatOfDouble();
            Core.meanStdDev(laplacian, mean, stdDev);
            double sharpness = stdDev.get(0, 0)[0] * stdDev.get(0, 0)[0];
            mean.release();
            stdDev.release();
            blurred.release();
            laplacian.release();

            Mat edges = new Mat();
            Imgproc.Canny(mat, edges, 64.0d, 192.0d);
            double edgeDensity = edges.empty()
                    ? 0.0d
                    : Core.countNonZero(edges) / (double) (width * height);
            edges.release();

            return new ImageFeatures.Stats(
                    pure.meanLuma,
                    pure.lumaStdDev,
                    Math.max(0.0d, sharpness),
                    Math.max(pure.edgeDensity, Math.min(1.0d, edgeDensity)),
                    pure.dominantRgb,
                    width,
                    height);
        } finally {
            mat.release();
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_DECODE_DIMENSION || height / sample > MAX_DECODE_DIMENSION) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyExifRotation(File input, Bitmap bitmap) {
        int rotation = 0;
        try (InputStream in = java.nio.file.Files.newInputStream(input.toPath())) {
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: rotation = 0;
            }
        } catch (Exception ignored) {
            rotation = 0;
        }
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }
}
