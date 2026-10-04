package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

public class WavCodecTest {
    @Test
    public void headerMatchesCanonicalRiffLayout() throws IOException {
        byte[] pcm = new byte[] {1, 2, 3, 4};
        byte[] wav = WavCodec.encode(pcm);
        assertEquals(44 + 4, wav.length);
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        // chunk size = 36 + pcm bytes, little endian at offset 4
        assertEquals(36 + 4, wav[4] & 0xFF | (wav[5] & 0xFF) << 8 | (wav[6] & 0xFF) << 16 | (wav[7] & 0xFF) << 24);
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
        assertEquals(16, wav[16] & 0xFF); // fmt chunk size LSB
        assertEquals(1, wav[20] & 0xFF); // PCM format
        assertEquals(1, wav[22] & 0xFF); // mono channel count LSB
        // data chunk
        assertEquals('d', wav[36]);
        assertEquals('a', wav[37]);
        assertEquals('t', wav[38]);
        assertEquals('a', wav[39]);
        assertEquals(4, wav[40] & 0xFF);
    }

    @Test
    public void durationMatchesPcmMath() {
        assertEquals(1_000L, WavCodec.durationMs(WavCodec.SAMPLE_RATE_HZ * 2));
        assertEquals(0L, WavCodec.durationMs(0));
        assertEquals(500L, WavCodec.durationMs(WavCodec.SAMPLE_RATE_HZ));
    }

    @Test
    public void patchSizesRewritesBothLengths() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WavCodec.writeHeader(out, 1_000);
        byte[] buffer = out.toByteArray();
        WavCodec.patchSizes(buffer, 8_000);
        int riff = buffer[4] & 0xFF | (buffer[5] & 0xFF) << 8 | (buffer[6] & 0xFF) << 16 | (buffer[7] & 0xFF) << 24;
        int data = buffer[40] & 0xFF | (buffer[41] & 0xFF) << 8 | (buffer[42] & 0xFF) << 16 | (buffer[43] & 0xFF) << 24;
        assertEquals(36 + 8_000, riff);
        assertEquals(8_000, data);
    }

    @Test
    public void rejectsOutOfRangePcm() {
        assertThrows(IllegalArgumentException.class, () -> WavCodec.encode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.encode(new byte[97_000_000]));
    }

    @Test
    public void streamHeaderIsFramed() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WavCodec.writeHeader(out, 1_000);
        assertEquals(44, out.size());
        assertTrue(out.toByteArray()[36] == 'd');
    }
}
