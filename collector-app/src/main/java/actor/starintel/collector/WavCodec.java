package actor.starintel.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Minimal PCM16 WAV writer/decoder helpers. Pure Java so unit tests can cover framing. */
public final class WavCodec {
    public static final int SAMPLE_RATE_HZ = 16_000;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    static final int HEADER_BYTES = 44;
    static final int MAX_PCM_BYTES = 96_000_000;

    private WavCodec() {}

    /** Writes a canonical 44-byte RIFF/WAVE header for PCM16 mono data of {@code pcmBytes}. */
    public static void writeHeader(OutputStream out, int pcmBytes) throws IOException {
        requirePcmBytes(pcmBytes);
        int byteRate = SAMPLE_RATE_HZ * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        int chunkSize = 36 + pcmBytes;
        out.write("RIFF".getBytes("US-ASCII"));
        le32(out, chunkSize);
        out.write("WAVE".getBytes("US-ASCII"));
        out.write("fmt ".getBytes("US-ASCII"));
        le32(out, 16);
        le16(out, 1);
        le16(out, CHANNELS);
        le32(out, SAMPLE_RATE_HZ);
        le32(out, byteRate);
        le16(out, blockAlign);
        le16(out, BITS_PER_SAMPLE);
        out.write("data".getBytes("US-ASCII"));
        le32(out, pcmBytes);
    }

    /** Encodes a complete WAV file (header + PCM) in memory; bounded to the segment cap. */
    public static byte[] encode(byte[] pcm) throws IOException {
        requirePcmBytes(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + pcm.length);
        writeHeader(out, pcm.length);
        out.write(pcm);
        return out.toByteArray();
    }

    /** Duration of PCM16 mono audio in milliseconds. */
    public static long durationMs(int pcmBytes) {
        if (pcmBytes <= 0) return 0L;
        long frames = pcmBytes / (long) (CHANNELS * BITS_PER_SAMPLE / 8);
        return Math.round(frames * 1000.0 / SAMPLE_RATE_HZ);
    }

    /** Rewrites the RIFF/data sizes in an already-padded header after streaming writes. */
    public static void patchSizes(byte[] fileStart, int pcmBytes) {
        if (fileStart == null || fileStart.length < HEADER_BYTES) {
            throw new IllegalArgumentException("WAV buffer too small");
        }
        requirePcmBytes(pcmBytes);
        putLe32(fileStart, 4, 36 + pcmBytes);
        putLe32(fileStart, 40, pcmBytes);
    }

    private static void requirePcmBytes(int pcmBytes) {
        if (pcmBytes <= 0 || pcmBytes > MAX_PCM_BYTES) {
            throw new IllegalArgumentException("PCM byte count out of range: " + pcmBytes);
        }
    }

    private static void le32(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void le16(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
