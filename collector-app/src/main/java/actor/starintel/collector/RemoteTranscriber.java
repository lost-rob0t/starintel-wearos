package actor.starintel.collector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Minimal OpenAI-compatible /v1/audio/transcriptions client. HTTPS-only outside debug builds. */
public final class RemoteTranscriber {
    static final long MAX_AUDIO_BYTES = 32L * 1_024 * 1_024;
    static final int MAX_RESPONSE_BYTES = 1_024 * 1_024;

    private RemoteTranscriber() {}

    public static String transcribe(String baseUrl, String apiKey, String model, File audio,
            String mediaType, String fileName) throws IOException {
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IOException("Remote transcription requires an HTTPS endpoint");
        }
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Remote transcription key missing");
        if (model == null || model.isEmpty()) throw new IOException("Remote transcription model missing");
        byte[] audioBytes = Files.readAllBytes(audio.toPath());
        if (audioBytes.length > MAX_AUDIO_BYTES) throw new IOException("Audio segment exceeds remote cap");

        String boundary = "starwireless-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(audioBytes.length + 1_024);
        addField(body, boundary, "model", model);
        addField(body, boundary, "response_format", "json");
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mediaType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(audioBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(body.size());
            connection.getOutputStream().write(body.toByteArray());

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String payload = readBounded(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("Remote transcription HTTP " + status + ": " + payload);
            }
            return payload;
        } finally {
            connection.disconnect();
        }
    }

    private static void addField(ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String readBounded(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > MAX_RESPONSE_BYTES) throw new IOException("Remote response exceeds cap");
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
