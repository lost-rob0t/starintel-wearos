package actor.starintel.collector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Owns the app-private whisper model file.
 *
 * Downloads are HTTPS-only, size-bounded, and SHA-256 pinned before they are moved
 * into place, mirroring the repository's update-catalog policy.
 */
public final class WhisperModelStore {
    public static final String DEFAULT_TAG = "base.en-q5_1";
    public static final String DEFAULT_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin";
    public static final String DEFAULT_SHA256 =
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f";
    public static final long DEFAULT_SIZE_BYTES = 59_721_011L;
    public static final long MAX_MODEL_BYTES = 256L * 1_024 * 1_024;

    private final File modelsDirectory;

    public WhisperModelStore(File filesDir) {
        this.modelsDirectory = new File(filesDir, "whisper-models");
    }

    public File modelFile(String tag) {
        return new File(modelsDirectory, "ggml-" + sanitizeTag(tag) + ".bin");
    }

    public boolean isDownloaded(String tag) {
        File file = modelFile(tag);
        return file.isFile() && file.length() > 0L && file.length() <= MAX_MODEL_BYTES;
    }

    /** Streams the pinned model into place; returns the installed file. */
    public File download(String tag, String httpsUrl, String expectedSha256, long expectedSize)
            throws IOException {
        requireHttps(httpsUrl);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Model SHA-256 pin missing or malformed");
        }
        if (expectedSize <= 0L || expectedSize > MAX_MODEL_BYTES) {
            throw new IOException("Model size pin out of range");
        }
        if (!modelsDirectory.isDirectory() && !modelsDirectory.mkdirs()) {
            throw new IOException("Could not create model directory");
        }

        File partial = File.createTempFile("whisper-model-", ".part", modelsDirectory);
        try {
            HttpURLConnection connection = open(httpsUrl);
            try {
                int status = connection.getResponseCode();
                if (status != 200) throw new IOException("Model download HTTP " + status);
                long declared = connection.getContentLengthLong();
                if (declared > MAX_MODEL_BYTES) throw new IOException("Declared model size exceeds cap");
                transfer(connection.getInputStream(), partial, expectedSize);
            } finally {
                connection.disconnect();
            }
            String actual = sha256(partial);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("Model SHA-256 mismatch: got " + actual);
            }
            if (partial.length() != expectedSize) {
                throw new IOException("Model size mismatch: got " + partial.length());
            }
            File target = modelFile(tag);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1_024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b));
        return hex.toString();
    }

    static void requireHttps(String url) throws IOException {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("Model downloads must use HTTPS");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Accept", "application/octet-stream");
        return connection;
    }

    private static void transfer(InputStream in, File target, long expectedSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
        try (OutputStream out = new FileOutputStream(target);
                InputStream source = in) {
            byte[] buffer = new byte[64 * 1_024];
            long total = 0L;
            int read;
            while ((read = source.read(buffer)) > 0) {
                total += read;
                if (total > MAX_MODEL_BYTES) {
                    throw new IOException("Model stream exceeds hard cap");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            if (total != expectedSize) {
                throw new IOException("Model stream truncated: " + total + " of " + expectedSize);
            }
        }
    }

    private static String sanitizeTag(String tag) {
        String clean = tag == null ? DEFAULT_TAG : tag.trim();
        if (clean.isEmpty()) clean = DEFAULT_TAG;
        if (!clean.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Model tag malformed");
        }
        return clean;
    }
}
