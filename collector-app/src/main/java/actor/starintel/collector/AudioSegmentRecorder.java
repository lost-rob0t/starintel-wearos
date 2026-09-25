package actor.starintel.collector;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records bounded WAV segments from the device microphone.
 *
 * The recorder only runs while the visible Collector foreground session is active and
 * the user has enabled audio collection. Segments are bounded so transcripts stay
 * incremental and spool size stays predictable.
 */
final class AudioSegmentRecorder {
    static final int SEGMENT_SECONDS = 30;
    static final long MAX_SPOOL_BYTES = 192L * 1_024 * 1_024;

    private final File spoolDirectory;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AudioRecord record;
    private volatile Thread worker;
    private volatile Listener listener;

    interface Listener {
        void onSegment(File wavFile, long durationMs, String sha256Hex);

        void onError(String message);
    }

    AudioSegmentRecorder(File filesDir) {
        this.spoolDirectory = new File(filesDir, "audio-spool");
    }

    void setListener(Listener value) {
        this.listener = value;
    }

    boolean isRunning() {
        return running.get();
    }

    void enforceSpoolCap() {
        File[] segments = spoolDirectory.listFiles((dir, name) -> name.endsWith(".wav"));
        if (segments == null) return;
        long total = 0L;
        for (File segment : segments) total += segment.length();
        if (total <= MAX_SPOOL_BYTES) return;
        java.util.Arrays.sort(segments, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        for (File segment : segments) {
            if (total <= MAX_SPOOL_BYTES) break;
            long size = segment.length();
            if (segment.delete()) total -= size;
        }
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (!running.compareAndSet(false, true)) return;
        if (!spoolDirectory.isDirectory() && !spoolDirectory.mkdirs()) {
            running.set(false);
            notifyError("Could not create audio spool directory");
            return;
        }
        enforceSpoolCap();

        int minBuffer = AudioRecord.getMinBufferSize(
                WavCodec.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            running.set(false);
            notifyError("AudioRecord unavailable");
            return;
        }
        int bufferSize = Math.max(minBuffer, WavCodec.SAMPLE_RATE_HZ); // >= 1s

        AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    WavCodec.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (RuntimeException failure) {
            running.set(false);
            notifyError("Microphone unavailable: " + message(failure));
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            running.set(false);
            notifyError("Microphone not initialized");
            return;
        }

        this.record = recorder;
        Thread thread = new Thread(this::recordLoop, "star-wireless-audio");
        this.worker = thread;
        thread.start();
    }

    void stop() {
        running.set(false);
        Thread thread = worker;
        if (thread != null) {
            try {
                thread.join(4_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        AudioRecord recorder = record;
        record = null;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            recorder.release();
        }
    }

    private void recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        AudioRecord recorder = record;
        if (recorder == null) return;

        int segmentFrames = WavCodec.SAMPLE_RATE_HZ * SEGMENT_SECONDS;
        byte[] pcm = new byte[segmentFrames * 2];
        int filled = 0;
        long startedAt = System.currentTimeMillis();
        recorder.startRecording();

        short[] chunk = new short[4_096];
        try {
            while (running.get()) {
                Thread.sleep(50L);
                int read = recorder.read(chunk, 0, chunk.length);
                if (read <= 0) continue;
                for (int index = 0; index < read; index++) {
                    pcm[filled * 2] = (byte) (chunk[index] & 0xFF);
                    pcm[filled * 2 + 1] = (byte) ((chunk[index] >> 8) & 0xFF);
                    filled++;
                }
                if (filled >= segmentFrames) {
                    finalizeSegment(pcm, filled, startedAt);
                    filled = 0;
                    startedAt = System.currentTimeMillis();
                }
            }
            if (filled > 0) finalizeSegment(pcm, filled, startedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            notifyError(message(failure));
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
        }
    }

    private void finalizeSegment(byte[] pcm, int frames, long startedAtMs) {
        long durationMs = WavCodec.durationMs(frames * 2);
        try {
            byte[] wav = WavCodec.encode(java.util.Arrays.copyOf(pcm, frames * 2));
            File segment = new File(spoolDirectory, "segment-" + UUID.randomUUID() + ".wav");
            try (OutputStream out = new FileOutputStream(segment)) {
                out.write(wav);
            }
            Listener sink = listener;
            if (sink != null) sink.onSegment(segment, durationMs, sha256Hex(wav));
        } catch (IOException failure) {
            notifyError(message(failure));
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(data)) hex.append(String.format(java.util.Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private void notifyError(String message) {
        Listener sink = listener;
        if (sink != null) sink.onError(message);
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isEmpty() ? failure.getClass().getSimpleName() : value;
    }
}
